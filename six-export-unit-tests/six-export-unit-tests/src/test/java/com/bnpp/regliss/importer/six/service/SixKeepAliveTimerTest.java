package com.bnpp.regliss.importer.six.service;

// TODO: re-add project imports (Alt+Enter / Optimize Imports) for the project classes used below

import org.junit.After;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SixKeepAliveTimerTest {

    @After
    public void clearInterruptFlag() {
        Thread.interrupted();
    }

    private static ScheduledExecutorService executorOf(SixKeepAliveTimer timer) {
        return (ScheduledExecutorService) ReflectionTestUtils.getField(timer, "executor");
    }

    @Test
    public void actionRunsEveryPeriod_errorsDoNotStopIt() throws InterruptedException {
        CountDownLatch twice = new CountDownLatch(2);
        try (SixKeepAliveTimer timer = SixKeepAliveTimer.start("t1", 0, 10, () -> {
            twice.countDown();
            throw new IllegalStateException("db");             // logged, next tick runs again
        })) {
            assertTrue(twice.await(10, TimeUnit.SECONDS));
            assertFalse(executorOf(timer).isShutdown());
        }
    }

    @Test
    public void stopsRefreshingAfterTheMaximumTime() throws InterruptedException {
        SixKeepAliveTimer timer = SixKeepAliveTimer.start("t2", 1, 0, () -> { });
        ScheduledExecutorService executor = executorOf(timer);
        assertTrue("timer stops itself", executor.awaitTermination(10, TimeUnit.SECONDS));
        timer.close();
    }

    @Test
    public void closeWaitsForARunningRefresh() throws InterruptedException {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SixKeepAliveTimer timer = SixKeepAliveTimer.start("t3", 1, 10, () -> {
            running.countDown();
            awaitQuietly(release, 20);
        });
        assertTrue(running.await(10, TimeUnit.SECONDS));
        timer.close();                                         // waits 5 s, refresh still running: logged
        release.countDown();
        assertTrue(executorOf(timer).isShutdown());
    }

    @Test
    public void closeInterrupted_flagRestored() throws InterruptedException {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SixKeepAliveTimer timer = SixKeepAliveTimer.start("t4", 1, 10, () -> {
            running.countDown();
            awaitQuietly(release, 20);
        });
        assertTrue(running.await(10, TimeUnit.SECONDS));
        Thread.currentThread().interrupt();
        timer.close();
        assertTrue(Thread.currentThread().isInterrupted());
        release.countDown();
    }

    private static void awaitQuietly(CountDownLatch latch, int seconds) {
        try {
            latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
