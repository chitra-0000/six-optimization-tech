package com.bnpp.regliss.importer.six.service;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

// TODO: re-add project imports for: ReglissRequestContext, User

/**
 * Worker pool used ONLY by the SIX import (SixBatchImportRunner).
 *
 * Same design as the shared BackpressureExecutor, which stays exactly as in production
 * for every other import:
 *  - N writer threads, and at most N more tasks waiting, so the file reader can never
 *    run far ahead of the database writers (bounded memory);
 *  - the caller's user / request context is copied onto each worker thread.
 *
 * Differences (why the SIX import has its own class):
 *  - waitUntilFinished() waits until every task has really finished (logging each
 *    minute) instead of giving up after 10 minutes and carrying on while rows are
 *    still being written;
 *  - a worker error is recorded inside the task itself (volatile field), so the submitting
 *    thread always sees it - not only through the thread's uncaught-exception handler.
 *
 * Not a Spring bean: one instance per imported file, created with "new" and shut down
 * at the end, exactly like BackpressureExecutor. No static or shared state, so it can
 * run at the same time as any other import without interfering.
 */
@Slf4j
public class SixImportExecutor {

    private final ReglissRequestContext requestContext;
    private final String threadBaseName;
    private final long slowWarningMinutes;
    private final Semaphore sem;
    private final ThreadPoolExecutor executor;
    private volatile Throwable exceptionFromWorker;

    /**
     * @param slowWarningMinutes log a WARN line each time NO batch has finished for this many
     *                           minutes. Warning only: the import is never stopped or reverted
     *                           for being slow. The application's 30-minute last-update check
     *                           remains the official "process stuck" alert.
     */
    public SixImportExecutor(String threadBaseName, int workerThreads, ReglissRequestContext requestContext,
                             long slowWarningMinutes) {
        this.requestContext = requestContext;
        this.threadBaseName = threadBaseName;
        this.slowWarningMinutes = Math.max(1, slowWarningMinutes);
        this.sem = new Semaphore(workerThreads);
        this.executor = new ThreadPoolExecutor(workerThreads, workerThreads, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(workerThreads), new WorkerThreadFactory());
        log.debug("[{}] started: {} threads, queue {}", threadBaseName, workerThreads, workerThreads);
    }

    /**
     * Blocks while N tasks are already waiting; rethrows a worker error if one occurred.
     * A slot frees up only when a running batch finishes. If none finishes for
     * slowWarningMinutes, a WARN is logged and the wait simply continues - slowness is
     * reported, never treated as an error.
     */
    public void blockingSubmit(Runnable task) {
        if (exceptionFromWorker != null) {
            waitUntilFinished();
            throw new RuntimeException("Exception occurred in SIX import worker", exceptionFromWorker);
        }
        try {
            long waited = 0;
            while (!sem.tryAcquire(slowWarningMinutes, TimeUnit.MINUTES)) {
                waited += slowWarningMinutes;
                log.warn("[{}] SLOW: no batch finished for {} min - still waiting ({} running, {} queued)",
                        threadBaseName, waited, executor.getActiveCount(), executor.getQueue().size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        executor.execute(() -> {
            sem.release();          // a waiting slot is free as soon as the task starts running
            try {
                task.run();
            } catch (RuntimeException | Error e) {
                // Recorded here, inside the task, so it is always visible once waitUntilFinished()
                // returns. (A thread's uncaught-exception handler can run AFTER the pool reports
                // termination, so relying on it alone can miss the error.)
                if (exceptionFromWorker == null) {
                    exceptionFromWorker = e;
                }
                log.error("[{}] worker task failed", threadBaseName, e);
            }
        });
    }

    /**
     * Stops accepting tasks and waits until every submitted task has really finished
     * (no time limit - a slow import is never cut off). Logs progress every minute and a
     * WARN every slowWarningMinutes.
     */
    public void waitUntilFinished() {
        executor.shutdown();
        try {
            long waited = 0;
            while (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
                waited++;
                log.info("[{}] still writing: {} running, {} queued, {} min waited",
                        threadBaseName, executor.getActiveCount(), executor.getQueue().size(), waited);
                if (waited % slowWarningMinutes == 0) {
                    log.warn("[{}] SLOW: last batches still running after {} min - still waiting",
                            threadBaseName, waited);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        if (exceptionFromWorker != null) {
            throw new RuntimeException("Exception occurred in SIX import worker", exceptionFromWorker);
        }
    }

    private class WorkerThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            User callerUser = requestContext.getCurrentUser();   // copied from the submitting thread
            Thread thread = new Thread(() -> {
                requestContext.setRequestTime(LocalDateTime.now());
                requestContext.setCurrentUser(callerUser);
                r.run();
            });
            thread.setName(threadBaseName + "-" + counter.getAndIncrement());
            thread.setUncaughtExceptionHandler((t, e) -> {
                exceptionFromWorker = e;
                log.error("[{}] uncaught exception in worker", threadBaseName, e);
            });
            return thread;
        }
    }
}
