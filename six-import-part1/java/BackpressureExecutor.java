package com.bnpp.regliss.importer.dj.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

// TODO: re-add project imports for: ReglissRequestContext, User, TimeStamps, PerformanceMonitor

/**
 * Same class as today, with three fixes (marked FIX):
 *
 * 1. shutdownAndWaitToDie() waited at most 10 MINUTES and ignored the result of
 *    awaitTermination(). A file import takes ~15 min today, so after 10 min the
 *    caller carried on as if the import were finished while workers were still
 *    inserting: the import was marked done, and the confidence poller could start
 *    its MERGE on a half-loaded version. Now it waits until the workers really stop
 *    (logging progress every minute) and fails loudly if they do not stop within
 *    the configured ceiling.
 * 2. exceptionFromWorker is written by worker threads and read by the submitting
 *    thread: it must be volatile, otherwise the submitter may never see it.
 * 3. counter in MyThreadFactory is only used from the pool's own thread creation,
 *    but it is made safe anyway (AtomicInteger) because the pool can create threads
 *    from execute() on the caller's thread.
 *
 * Behaviour kept on purpose: the semaphore is released when a task STARTS, so up to
 * N tasks run and up to N wait in the queue. The producer can map the next batch
 * while all writers are busy. The queue (capacity N) can never overflow because a
 * task only enters it while holding one of the N permits.
 */
public class BackpressureExecutor {
    private static final Logger log = LoggerFactory.getLogger(BackpressureExecutor.class);

    /** FIX 1: hard ceiling for waiting on workers. A SIX file import is minutes, not hours. */
    private static final long MAX_WAIT_MINUTES = 180;

    private final ReglissRequestContext requestContext;
    private final String threadBaseName;

    private final Semaphore sem;
    private final ThreadPoolExecutor executor;
    private volatile Throwable exceptionFromWorker;              // FIX 2

    private TimeStamps timeStamps = new TimeStamps();

    public BackpressureExecutor(String threadBaseName, int workerThreads, ReglissRequestContext requestContext) {
        this.requestContext = requestContext;
        this.threadBaseName = threadBaseName;

        sem = new Semaphore(workerThreads);
        executor = new ThreadPoolExecutor(workerThreads, workerThreads, 0,
                TimeUnit.SECONDS, new ArrayBlockingQueue<>(workerThreads), new MyThreadFactory());
        log.debug("Started thread pool: threads: {} , queue size: {}", workerThreads, workerThreads);
    }

    private class MyThreadFactory implements ThreadFactory {
        private final java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger(); // FIX 3

        @Override
        public Thread newThread(Runnable r) {
            r = propagateRequestContext(r);
            r = collectStatsAtEnd(r);
            Thread thread = new Thread(r);
            thread.setName(threadBaseName + "-" + counter.getAndIncrement());
            thread.setUncaughtExceptionHandler((Thread t, Throwable exception) -> {
                exceptionFromWorker = exception;
                log.debug("Caught exception in worker: " + exception, exception);
            });
            return thread;
        }

        private Runnable collectStatsAtEnd(Runnable r) {
            return () -> {
                r.run();
                synchronized (BackpressureExecutor.this) {
                    timeStamps = timeStamps.add(PerformanceMonitor.current());
                    log.debug("Worker stopped. Collected its performance stats.");
                }
            };
        }

        public Runnable propagateRequestContext(Runnable runnable) {
            log.debug("Propagating from thread with user id = {}", requestContext.getUserId());
            User callerUser = requestContext.getCurrentUser();
            return () -> {
                requestContext.setRequestTime(LocalDateTime.now());
                requestContext.setCurrentUser(callerUser);
                log.debug("Restored user id {} on child worker thread", callerUser.getId());
                runnable.run();
            };
        }
    }

    public void blockingSubmit(Runnable task) {
        if (exceptionFromWorker != null) {
            log.warn("Will throw back exception from worker after all other workers stop: ", exceptionFromWorker);
            shutdownAndWaitToDie();
            log.warn("Throwing back exception from worker: ", exceptionFromWorker);
            throw new RuntimeException("Exception occured in worker", exceptionFromWorker);
        }
        try {
            sem.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        task = new RunnableReleasingSemaphore(task);
        executor.execute(task);
    }

    private class RunnableReleasingSemaphore implements Runnable {
        private final Runnable runnable;

        public RunnableReleasingSemaphore(Runnable runnable) { this.runnable = runnable; }

        public void run() {
            sem.release();
            runnable.run();
        }
    }

    public void shutdownAndWaitToDie() {
        log.debug("Awaiting termination of tasks: running: {}, in queue:{}", executor.getActiveCount(), executor.getQueue().size());
        executor.shutdown();
        try {
            // FIX 1: wait for real, in 1-minute steps, instead of giving up silently after 10 minutes.
            long waited = 0;
            while (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
                waited++;
                log.info("[{}] still writing: {} running, {} queued, {} min waited",
                        threadBaseName, executor.getActiveCount(), executor.getQueue().size(), waited);
                if (waited >= MAX_WAIT_MINUTES) {
                    executor.shutdownNow();
                    throw new IllegalStateException("[" + threadBaseName + "] workers did not finish within "
                            + MAX_WAIT_MINUTES + " minutes; import aborted");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        log.debug("UPDATE executor shut down");
        log.debug("EXECUTOR ({} threads): {}", executor.getCorePoolSize(), timeStamps);
        if (exceptionFromWorker != null) {
            log.debug("Worker thread threw exception. Throwing it back in the main thread...");
            throw new RuntimeException("Exception occured worker", exceptionFromWorker);
        }
    }
}
