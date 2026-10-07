package com.bnpp.regliss.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * REQUIREMENT 3: Handles HikariCP connection pool exhaustion scenarios
 *
 * Root causes of pool exhaustion in dual-server environment:
 * 1. One server's batch filtering takes too long, holds all connections
 * 2. One server's XML generation reads large datasets, holds connections during read
 * 3. Both servers competing for connections on same database
 * 4. Filtering stage not releasing connections fast enough before next list starts
 *
 * Recovery Strategy:
 * - Exponential backoff retry with coordinated delays
 * - Maximum 3 retries per operation
 * - Delays: 1s, 2s, 4s, 8s, 16s, 32s (max 30s)
 * - Other server gets opportunity to release connections during wait
 * - Graceful failure if all retries exhausted
 *
 * Prevention:
 * - Monitor connection pool health continuously
 * - Reduce batch size dynamically if pool stress detected
 * - Set connection timeout < 30s to fail fast
 * - Implement connection pooling per operation stage
 *
 * SONAR FIXES:
 * - Added null checks for connectionPoolMonitor
 * - Improved error logging with proper parametrization
 * - Better thread-local cleanup handling
 *
 * FORTIFY FIXES:
 * - Added robust null pointer checks
 * - Improved exception handling and logging
 * - Thread-safe retry counter management
 */
@Component
@Slf4j
public class PoolExhaustionHandler {

    /** Exponential backoff delays in milliseconds */
    private static final long[] RETRY_DELAYS_MS = {1000, 2000, 4000, 8000, 16000, 30000};
    private static final int MAX_RETRIES = 3;
    private static final String POOL_ERROR_PREFIX = "SIX XML: Pool exhaustion in ";

    @Autowired(required = false)
    private ConnectionPoolMonitor connectionPoolMonitor;

    /** Thread-safe retry counter per operation */
    private static final ThreadLocal<AtomicInteger> RETRY_COUNTER = ThreadLocal.withInitial(AtomicInteger::new);

    /**
     * Executes operation with pool exhaustion recovery
     *
     * @param operationName - Name of operation for logging
     * @param operation - The database operation to execute
     * @param <T> - Return type
     * @return Result of successful operation
     * @throws RuntimeException if max retries exceeded
     */
    public <T> T executeWithPoolRecovery(String operationName, Supplier<T> operation) {
        AtomicInteger retryCounter = RETRY_COUNTER.get();
        retryCounter.set(0);

        while (retryCounter.get() <= MAX_RETRIES) {
            try {
                return operation.get();
            } catch (RuntimeException e) {
                if (isPoolExhaustionError(e) && retryCounter.get() < MAX_RETRIES) {
                    handlePoolExhaustion(operationName, e, retryCounter);
                    retryCounter.incrementAndGet();
                } else {
                    RETRY_COUNTER.remove();
                    int attemptCount = retryCounter.get() + 1;
                    throw new PoolExhaustionException(
                            "Operation '" + operationName + "' failed after " + attemptCount + " attempts",
                            e);
                }
            }
        }

        RETRY_COUNTER.remove();
        throw new PoolExhaustionException("Operation '" + operationName + "' exhausted retries");
    }

    /**
     * Handles pool exhaustion by:
     * 1. Logging detailed diagnostic info
     * 2. Waiting with exponential backoff
     * 3. Giving other server opportunity to release connections
     *
     * FORTIFY FIX: Added null checks for monitor
     * SONAR FIX: Improved logging parametrization
     */
    private void handlePoolExhaustion(String operationName, RuntimeException error, AtomicInteger retryCounter) {
        int retryAttempt = retryCounter.get();
        long delayMs = RETRY_DELAYS_MS[Math.min(retryAttempt, RETRY_DELAYS_MS.length - 1)];

        log.warn("{}{} (attempt {}/{}) - {}. Waiting {} ms for connection release...",
                POOL_ERROR_PREFIX, operationName, retryAttempt + 1, MAX_RETRIES,
                extractErrorMessage(error), delayMs);

        // Log pool metrics if monitor available
        if (connectionPoolMonitor != null) {
            try {
                String metrics = connectionPoolMonitor.getPoolMetricsString();
                log.warn("SIX XML: Current pool metrics: {}", metrics);
            } catch (Exception e) {
                log.debug("Could not fetch pool metrics: {}", e.getMessage());
            }
        }

        // Exponential backoff - gives other server time to release connections
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during pool recovery wait", ie);
        }

        log.info("SIX XML: Retrying {} after {} ms wait", operationName, delayMs);
    }

    /**
     * Safely extracts error message without null pointer risk
     */
    private String extractErrorMessage(RuntimeException e) {
        if (e == null) {
            return "Unknown error";
        }
        String message = e.getMessage();
        return message != null ? message : e.getClass().getSimpleName();
    }

    /**
     * Detects if RuntimeException is pool-related
     *
     * FORTIFY FIX: Improved null checks on cause
     */
    private boolean isPoolExhaustionError(RuntimeException e) {
        if (e == null) {
            return false;
        }

        String msg = e.getMessage();
        if (msg == null) {
            msg = "";
        }
        msg = msg.toLowerCase();

        String cause = "";
        if (e.getCause() != null && e.getCause().toString() != null) {
            cause = e.getCause().toString().toLowerCase();
        }

        return msg.contains("hikari") ||
               msg.contains("connection pool") ||
               msg.contains("connection timeout") ||
               msg.contains("unable to acquire") ||
               msg.contains("timeout waiting for idle object") ||
               msg.contains("pool exhausted") ||
               cause.contains("hikari") ||
               cause.contains("connection pool") ||
               cause.contains("timeout") ||
               cause.contains("unable to acquire");
    }

    /**
     * Custom exception for pool exhaustion scenarios
     */
    public static class PoolExhaustionException extends RuntimeException {
        public PoolExhaustionException(String message) {
            super(message);
        }

        public PoolExhaustionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Cleanup thread-local resources
     * SONAR FIX: Ensure proper cleanup
     */
    public static void cleanup() {
        try {
            RETRY_COUNTER.remove();
        } catch (Exception e) {
            log.debug("Error during thread-local cleanup: {}", e.getMessage());
        }
    }
}
