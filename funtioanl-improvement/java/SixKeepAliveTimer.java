package com.bnpp.regliss.importer.six.service;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs a keep-alive action every {@code periodSeconds} on a daemon thread while a long step works (SIX XML step:
 * the build of one CONVERTER file). Stopped by {@link #close()} (try-with-resources), so it never outlives the step:
 *  - the step ends or fails -> close() in the same thread stops it;
 *  - the server stops -> the daemon thread dies with the JVM, nothing is refreshed any more;
 *  - the step hangs -> refreshing stops after {@code maxMinutes}, so a stuck build turns KO like any dead job.
 * An error of the action is logged and does not stop the timer (the next tick tries again).
 *
 * Not a Spring bean: one instance per step.
 */
@Slf4j
public final class SixKeepAliveTimer implements AutoCloseable {

    private final ScheduledExecutorService executor;
    private final String name;

    private SixKeepAliveTimer(String name, long periodSeconds, long maxMinutes, Runnable action) {
        this.name = name;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "six-keepalive-" + name);
            t.setDaemon(true);
            return t;
        });
        long period = Math.max(1, periodSeconds);
        long maxMillis = TimeUnit.MINUTES.toMillis(maxMinutes);
        long start = System.currentTimeMillis();
        executor.scheduleAtFixedRate(() -> tick(action, start, maxMillis), period, period, TimeUnit.SECONDS);
    }

    /** Starts the timer; the first refresh comes after {@code periodSeconds}. */
    public static SixKeepAliveTimer start(String name, long periodSeconds, long maxMinutes, Runnable action) {
        return new SixKeepAliveTimer(name, periodSeconds, maxMinutes, action);
    }

    private void tick(Runnable action, long start, long maxMillis) {
        if (System.currentTimeMillis() - start > maxMillis) {
            log.warn("Keep-alive of {} stopped after {} minutes: the step is still running, it is not refreshed any more",
                    name, TimeUnit.MILLISECONDS.toMinutes(maxMillis));
            executor.shutdown();
            return;
        }
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("Keep-alive of {} failed (next try in the next tick): {}", name, e.getMessage());
        }
    }

    /** Stops the timer; a refresh already running is not interrupted and finishes (at most a few seconds are waited). */
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.debug("Keep-alive of {}: last refresh still running", name);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
