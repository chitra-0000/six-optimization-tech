package com.bnpp.regliss.importer.six.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

// TODO: re-add project import for: ReglissBatchProfile

/**
 * SIX part 4: JDBC statements of the nightly cleanup (SixCleanupService). Table names are constants of this class only
 * (never built from input: Fortify SQL injection).
 *
 *  - FILTERED_SIX_TARGET, FILTERED_SIX_INSTRUMENTS / _STRUCTURED / _OPTION: TRUNCATE (the parents with CASCADE, needed
 *    by Oracle because FILTERED_SIX_TARGET references them, ON DELETE CASCADE in V2_469). When TRUNCATE is refused
 *    (rights, Oracle below 12c, ...) the table is emptied by chunked DELETEs instead.
 *  - SIX_FILTERED_POLLER: only LOCKED while the filtered tables are emptied (its rows are removed by the automatic
 *    export itself, SixFilteredStore.deleteOlderVersionRows).
 *  - SIX_INSTRUMENTS / SIX_STRUCTURED / SIX_OPTION: chunked DELETE of the versions OLDER than the given one
 *    (VERSION_ID &lt; latest, index IDX_SIX_*_VERSION); SIX_TARGET rows go with them (ON DELETE CASCADE, V2_470).
 *    A version imported while the cleanup runs has a higher id and is never touched. The VERSION table is never touched.
 */
@Component
@ReglissBatchProfile
@Slf4j
public class SixCleanupStore {

    /** Raw SIX tables (latest version kept), with their SixFileKind (same package). */
    public enum RawTable {
        INSTRUMENTS("SIX_INSTRUMENTS", SixFileKind.INSTRUMENT),
        STRUCTURED("SIX_STRUCTURED", SixFileKind.STRUCTURED),
        OPTIONS("SIX_OPTION", SixFileKind.OPTIONS);

        private final String table;
        private final SixFileKind kind;

        RawTable(String table, SixFileKind kind) {
            this.table = table;
            this.kind = kind;
        }

        public String getTable() {
            return table;
        }

        /** SIX_FILTERED_POLLER.FILE_TYPE of the export of this raw table. */
        public String getPollerFileType() {
            return kind.getPollerFileType();
        }
    }

    static final String POLLER_TABLE = "SIX_FILTERED_POLLER";
    /** FILTERED_SIX_TARGET first (nothing references it), then its parents. */
    static final List<String> FILTERED_TABLES = Collections.unmodifiableList(Arrays.asList(
            "FILTERED_SIX_TARGET", "FILTERED_SIX_INSTRUMENTS", "FILTERED_SIX_STRUCTURED", "FILTERED_SIX_OPTION"));
    private static final String CHILD_TABLE = "FILTERED_SIX_TARGET";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** Rows per DELETE chunk (each chunk is its own short transaction; SIX_TARGET rows are deleted with them). */
    @Value("${six.cleanup.delete.chunk.size:2000}")
    private int chunkSize;

    private TransactionTemplate newTx;

    @PostConstruct
    void init() {
        newTx = new TransactionTemplate(transactionManager);
        newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Outcome of {@link #clearFilteredTables}. */
    public enum ClearResult { CLEARED, BUSY }

    /**
     * Empties the filtered tables (FILTERED_SIX_TARGET and FILTERED_SIX_*), under an EXCLUSIVE lock of
     * SIX_FILTERED_POLLER: an export that starts meanwhile first writes its PENDING rows there, so it waits until the
     * tables are empty and cannot lose filtered rows. {@code stillIdle} is checked again under the lock.
     * The TRUNCATEs run on their own connection (a TRUNCATE commits), the lock is released by the final commit.
     *
     * @throws DataAccessException when the lock is held by an export (NOWAIT): nothing is removed
     */
    public ClearResult clearFilteredTables(BooleanSupplier stillIdle) {
        ClearResult result = newTx.execute(status -> {
            jdbcTemplate.execute("LOCK TABLE " + POLLER_TABLE + " IN EXCLUSIVE MODE NOWAIT");
            if (!stillIdle.getAsBoolean()) {
                return ClearResult.BUSY;
            }
            for (String table : FILTERED_TABLES) {
                emptyTable(table);
            }
            return ClearResult.CLEARED;
        });
        return result == null ? ClearResult.BUSY : result;
    }

    /** TRUNCATE on its own connection; chunked DELETE when the TRUNCATE is refused (not for a lost DB connection). */
    private void emptyTable(String table) {
        String truncate = "TRUNCATE TABLE " + table + (CHILD_TABLE.equals(table) ? "" : " CASCADE");
        try {
            newTx.execute(status -> {
                jdbcTemplate.execute(truncate);
                return null;
            });
            log.info("SIX cleanup: {} truncated", table);
        } catch (DataAccessException e) {
            if (SixExportException.isConnectionError(e)) {
                throw e;
            }
            log.warn("SIX cleanup: TRUNCATE of {} refused ({}), rows deleted instead", table, SixExportException.rootCause(e));
            int rows = deleteInChunks("DELETE FROM " + table + " WHERE ID IS NOT NULL AND ROWNUM <= ?");
            log.info("SIX cleanup: {} row(s) removed from {}", rows, table);
        }
    }

    /** True when the raw table holds at least one row of this version. */
    public boolean hasRowsOfVersion(RawTable raw, long versionId) {
        Integer n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + raw.getTable() + " WHERE VERSION_ID = ? AND ROWNUM <= ?",
                Integer.class, versionId, 1);
        return n != null && n > 0;
    }

    /**
     * Deletes the rows of every version older than {@code latestVersionId}, chunk by chunk.
     *
     * @param stillIdle checked before every chunk: the delete stops as soon as a SIX job starts
     * @return rows removed
     */
    public int deleteOlderVersions(RawTable raw, long latestVersionId, BooleanSupplier stillIdle) {
        String sql = "DELETE FROM " + raw.getTable() + " WHERE VERSION_ID < ? AND ROWNUM <= ?";
        int chunk = chunk();
        int total = 0;
        int deleted;
        do {
            if (!stillIdle.getAsBoolean()) {
                log.info("SIX cleanup: a SIX job started, delete of {} stopped after {} row(s)", raw.getTable(), total);
                return total;
            }
            Integer n = newTx.execute(status -> jdbcTemplate.update(sql, latestVersionId, chunk));
            deleted = n == null ? 0 : n;
            total += deleted;
        } while (deleted >= chunk);
        return total;
    }

    private int deleteInChunks(String sql) {
        int chunk = chunk();
        int total = 0;
        int deleted;
        do {
            Integer n = newTx.execute(status -> jdbcTemplate.update(sql, chunk));
            deleted = n == null ? 0 : n;
            total += deleted;
        } while (deleted >= chunk);
        return total;
    }

    /** Chunk size, at least 1 (a wrong property value cannot loop for ever). */
    private int chunk() {
        return Math.max(1, chunkSize);
    }
}
