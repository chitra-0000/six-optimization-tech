package com.bnpp.regliss.importer.six.service;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// TODO: re-add project imports for: ReglissRequestContext, User

@RunWith(MockitoJUnitRunner.Silent.class)
public class SixImportExecutorTest {

    @Mock
    private ReglissRequestContext requestContext;

    @Mock
    private User user;

    @Before
    public void setUp() {
        when(requestContext.getCurrentUser()).thenReturn(user);
    }

    @After
    public void clearInterruptFlag() {
        Thread.interrupted();
    }

    @SuppressWarnings("unchecked")
    private static Throwable recordedError(SixImportExecutor executor) {
        return ((AtomicReference<Throwable>) ReflectionTestUtils.getField(executor, "exceptionFromWorker")).get();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    public void runsEveryTaskWithTheCallersUser() {
        SixImportExecutor executor = new SixImportExecutor("test", 2, requestContext);
        AtomicInteger done = new AtomicInteger();
        for (int i = 0; i < 10; i++) {
            executor.blockingSubmit(done::incrementAndGet);
        }
        executor.waitUntilFinished();
        assertEquals(10, done.get());
        verify(requestContext, atLeastOnce()).setCurrentUser(user);
        verify(requestContext, atLeastOnce()).setRequestTime(any(LocalDateTime.class));
    }

    @Test
    public void workerNamesUseTheBaseName() {
        SixImportExecutor executor = new SixImportExecutor("six-import-x", 1, requestContext);
        AtomicReference<String> name = new AtomicReference<>();
        executor.blockingSubmit(() -> name.set(Thread.currentThread().getName()));
        executor.waitUntilFinished();
        assertEquals("six-import-x-0", name.get());
    }

    @Test
    public void runtimeErrorOfATaskIsRethrownAtTheEnd() {
        SixImportExecutor executor = new SixImportExecutor("test", 1, requestContext);
        IllegalStateException boom = new IllegalStateException("db down");
        executor.blockingSubmit(() -> {
            throw boom;
        });
        try {
            executor.waitUntilFinished();
            fail("expected SixImportException");
        } catch (SixImportException e) {
            assertSame(boom, e.getCause());
        }
    }

    /**
     * One writer thread, so the tasks run one after the other: when the second task runs, the first one has
     * finished and its error is recorded. No sleep / polling needed.
     */
    @Test
    public void submitAfterAWorkerErrorThrows() throws InterruptedException {
        SixImportExecutor executor = new SixImportExecutor("test", 1, requestContext);
        CountDownLatch firstMayFail = new CountDownLatch(1);
        CountDownLatch secondRan = new CountDownLatch(1);
        executor.blockingSubmit(() -> {
            awaitQuietly(firstMayFail);
            throw new IllegalStateException("first batch failed");
        });
        executor.blockingSubmit(secondRan::countDown);       // accepted: the first task has not failed yet
        firstMayFail.countDown();
        assertTrue("second task ran", secondRan.await(10, TimeUnit.SECONDS));
        assertNotNull(recordedError(executor));
        try {
            executor.blockingSubmit(() -> { });
            fail("expected SixImportException");
        } catch (SixImportException e) {
            assertEquals("first batch failed", e.getCause().getMessage());
        }
    }

    @Test
    public void jvmErrorOfATaskIsRecordedAsAFailure() {
        SixImportExecutor executor = new SixImportExecutor("test", 1, requestContext);
        executor.blockingSubmit(() -> {
            throw new AssertionError("simulated JVM error");
        });
        try {
            executor.waitUntilFinished();
            fail("expected SixImportException");
        } catch (SixImportException e) {
            assertTrue(e.getCause() instanceof SixImportException);
            assertEquals("SIX import worker stopped by a JVM error", e.getCause().getMessage());
        }
    }

    @Test
    public void uncaughtExceptionHandlerRecordsAnErrorThrownOutsideTheTask() throws InterruptedException {
        SixImportExecutor executor = new SixImportExecutor("test", 1, requestContext);
        ThreadPoolExecutor pool = (ThreadPoolExecutor) ReflectionTestUtils.getField(executor, "executor");
        Thread worker = pool.getThreadFactory().newThread(() -> {
            throw new IllegalArgumentException("outside the task");
        });
        worker.start();
        worker.join(10_000);
        assertEquals("outside the task", recordedError(executor).getMessage());
        try {
            executor.waitUntilFinished();
            fail("expected SixImportException");
        } catch (SixImportException e) {
            assertEquals("outside the task", e.getCause().getMessage());
        }
    }

    @Test
    public void interruptedWhileWaitingForAWriterSlot() {
        SixImportExecutor executor = new SixImportExecutor("test", 1, requestContext);
        Thread.currentThread().interrupt();
        try {
            executor.blockingSubmit(() -> { });
            fail("expected SixImportException");
        } catch (SixImportException e) {
            assertTrue(e.getCause() instanceof InterruptedException);
            assertTrue("interrupt flag restored", Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
            executor.waitUntilFinished();
        }
    }

    @Test
    public void interruptedWhileWaitingForTheWritersToFinish() {
        SixImportExecutor executor = new SixImportExecutor("test", 1, requestContext);
        CountDownLatch release = new CountDownLatch(1);
        executor.blockingSubmit(() -> {
            awaitQuietly(release);
        });
        Thread.currentThread().interrupt();
        try {
            executor.waitUntilFinished();
            fail("expected SixImportException");
        } catch (SixImportException e) {
            assertTrue(e.getCause() instanceof InterruptedException);
            assertTrue("interrupt flag restored", Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
            release.countDown();
        }
    }

    @Test
    public void keepsWaitingWhileTheLastBatchesAreStillWritten() throws InterruptedException {
        SixImportExecutor executor = new SixImportExecutor("test", 1, requestContext);
        ThreadPoolExecutor slowPool = mock(ThreadPoolExecutor.class);
        when(slowPool.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(false, false, true);
        ReflectionTestUtils.setField(executor, "executor", slowPool);

        executor.waitUntilFinished();

        verify(slowPool).shutdown();
        verify(slowPool, times(3)).awaitTermination(1, TimeUnit.MINUTES);
        assertFalse(Thread.currentThread().isInterrupted());
    }
}
