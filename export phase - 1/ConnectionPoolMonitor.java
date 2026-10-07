package com.bnpp.regliss.scheduler;

import com.zaxxer.hikari.HikariPoolMXBean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;

/**
 * REQUIREMENT 3: Monitors HikariCP connection pool health
 *
 * Purpose:
 * - Detect pool exhaustion before it causes failures
 * - Provide metrics for diagnostics and alerting
 * - Support dynamic batch size reduction based on pool stress
 * - Alert when pool is approaching saturation
 *
 * Prevention Strategy:
 * - Monitor active connections continuously
 * - If active connections > 80% of max, reduce batch size
 * - If active connections > 95% of max, pause XML generation
 * - Alert operations when pool is stressed
 *
 * SONAR FIXES:
 * - Made lastAlertTime volatile for thread safety
 * - Better null checking in metrics retrieval
 * - Improved error handling
 *
 * FORTIFY FIXES:
 * - Thread-safe lastAlertTime using volatile keyword
 * - Robust null pointer checking
 * - Safe exception handling without information disclosure
 */
@Component
@Slf4j
public class ConnectionPoolMonitor {

    @Autowired(required = false)
    private DataSource dataSource;

    private static final int POOL_STRESS_THRESHOLD_PCT = 80;
    private static final int POOL_CRITICAL_THRESHOLD_PCT = 95;
    private static final int CHECK_INTERVAL_MS = 5000; // Check every 5 seconds

    private HikariPoolMXBean hikariMxBean;

    // FORTIFY FIX: Made volatile for thread-safe visibility across threads
    private volatile long lastAlertTime = 0;
    private static final long ALERT_COOLDOWN_MS = 60000; // Don't spam alerts more than once per minute

    public ConnectionPoolMonitor() {
        initializeMonitoring();
    }

    /**
     * Initialize HikariCP MXBean for monitoring
     * SONAR FIX: Better null handling in initialization
     */
    private void initializeMonitoring() {
        try {
            if (dataSource instanceof com.zaxxer.hikari.HikariDataSource) {
                com.zaxxer.hikari.HikariDataSource hikariDs = (com.zaxxer.hikari.HikariDataSource) dataSource;
                this.hikariMxBean = hikariDs.getHikariPoolMXBean();
                log.info("HikariCP pool monitoring initialized");
            } else {
                log.debug("DataSource is not HikariDataSource, monitoring unavailable");
                this.hikariMxBean = null;
            }
        } catch (Exception e) {
            log.debug("Could not initialize HikariCP monitoring: {}", e.getMessage());
            this.hikariMxBean = null;
        }
    }

    /**
     * Gets current pool health metrics
     * FORTIFY FIX: Robust null checking
     * SONAR FIX: Better error handling
     */
    public PoolMetrics getPoolMetrics() {
        if (hikariMxBean == null || dataSource == null) {
            return new PoolMetrics("N/A", -1, -1, -1, false);
        }

        try {
            if (dataSource instanceof com.zaxxer.hikari.HikariDataSource) {
                com.zaxxer.hikari.HikariDataSource hikariDs = (com.zaxxer.hikari.HikariDataSource) dataSource;
                HikariPoolMXBean mxBean = hikariDs.getHikariPoolMXBean();

                if (mxBean == null) {
                    return new PoolMetrics(hikariDs.getPoolName() != null ? hikariDs.getPoolName() : "Unknown",
                            -1, -1, -1, false);
                }

                int activeConnections = mxBean.getActiveConnections();
                int totalConnections = mxBean.getTotalConnections();
                int idleConnections = mxBean.getIdleConnections();

                double utilizationPercent = totalConnections > 0 ?
                        (activeConnections * 100.0) / totalConnections : 0;

                boolean isStressed = utilizationPercent >= POOL_STRESS_THRESHOLD_PCT;
                boolean isCritical = utilizationPercent >= POOL_CRITICAL_THRESHOLD_PCT;

                return new PoolMetrics(
                        hikariDs.getPoolName(),
                        activeConnections,
                        totalConnections,
                        idleConnections,
                        isStressed,
                        isCritical,
                        utilizationPercent
                );
            }
        } catch (RuntimeException e) {
            log.debug("Error getting pool metrics: {}", e.getMessage());
        }

        return new PoolMetrics("Unknown", -1, -1, -1, false);
    }

    /**
     * Checks if pool is approaching exhaustion
     * SONAR FIX: Improved logging and null checks
     */
    public boolean isPoolStressed() {
        PoolMetrics metrics = getPoolMetrics();

        if (metrics.isStressed) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastAlertTime > ALERT_COOLDOWN_MS) {
                log.warn("SIX XML: Pool stress detected - {} active of {} total ({:.1f}% utilized)",
                        metrics.activeConnections, metrics.totalConnections, metrics.utilizationPercent);
                lastAlertTime = currentTime;
            }
            return true;
        }

        return false;
    }

    /**
     * Checks if pool is in critical state (should halt operations)
     * SONAR FIX: Better logging with parametrization
     */
    public boolean isPoolCritical() {
        PoolMetrics metrics = getPoolMetrics();

        if (metrics.isCritical) {
            log.error("SIX XML: CRITICAL pool exhaustion - {} active of {} total ({:.1f}% utilized). " +
                    "Pausing operations to allow recovery.",
                    metrics.activeConnections, metrics.totalConnections, metrics.utilizationPercent);
            return true;
        }

        return false;
    }

    /**
     * Suggests batch size based on pool health
     * Returns:
     *  - Original batch size if pool healthy
     *  - 50% of original if pool stressed
     *  - 25% of original if pool critical
     *
     * FORTIFY FIX: Safe integer operations with proper checks
     */
    public int getRecommendedBatchSize(int originalBatchSize) {
        if (originalBatchSize <= 0) {
            return 1;
        }

        PoolMetrics metrics = getPoolMetrics();

        if (metrics.isCritical) {
            return Math.max(1, originalBatchSize / 4);
        } else if (metrics.isStressed) {
            return Math.max(1, originalBatchSize / 2);
        }

        return originalBatchSize;
    }

    /**
     * Returns formatted string with pool metrics
     * SONAR FIX: Safe null handling for poolName
     */
    public String getPoolMetricsString() {
        PoolMetrics m = getPoolMetrics();
        String poolName = m.poolName != null ? m.poolName : "Unknown";
        return String.format("Pool(%s): %d active / %d total / %d idle (%.1f%% utilized)",
                poolName, m.activeConnections, m.totalConnections, m.idleConnections, m.utilizationPercent);
    }

    /**
     * Container for pool metrics
     * SONAR FIX: Improved null safety
     */
    public static class PoolMetrics {
        public final String poolName;
        public final int activeConnections;
        public final int totalConnections;
        public final int idleConnections;
        public final boolean isStressed;
        public final boolean isCritical;
        public final double utilizationPercent;

        // Constructors for different scenarios
        public PoolMetrics(String poolName, int active, int total, int idle, boolean stressed) {
            this(poolName, active, total, idle, stressed, false, -1.0);
        }

        public PoolMetrics(String poolName, int active, int total, int idle, boolean stressed,
                          boolean critical, double utilPercent) {
            this.poolName = poolName != null ? poolName : "Unknown";
            this.activeConnections = active;
            this.totalConnections = total;
            this.idleConnections = idle;
            this.isStressed = stressed;
            this.isCritical = critical;
            this.utilizationPercent = utilPercent;
        }
    }
}
