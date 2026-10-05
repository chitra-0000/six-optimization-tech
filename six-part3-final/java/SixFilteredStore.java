package com.bnpp.regliss.importer.six.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// TODO: re-add project import for: ReglissBatchProfile

/**
 * JDBC access of the SIX export to FILTERED_SIX_* and SIX_FILTERED_POLLER.
 *
 * Deletes:
 *  - always in a loop of small chunks (each chunk its own short transaction) until nothing is left. The old
 *    repository deletes ran ONCE with "ROWNUM <= batch": every row above the batch size stayed in the table;
 *  - FILTERED_SIX_TARGET rows go with their parent (ON DELETE CASCADE, indexed by V2_469);
 *  - no PARALLEL hint (it made Oracle start parallel slaves for every 1000-row delete).
 *
 * Poller rows (SIX_FILTERED_POLLER): one row per output list and per job (raw file). FILE_TYPE = INSTR / STRUCT /
 *  OPTIONS (unchanged), STATUS = new column:
 *    PENDING -> READY -> BUILDING -> DONE
 *       \-> DROPPED (list failed / skipped / export stopped)      \-> DROPPED (XML failed)
 *  Every status change also sets UPDATED_TIME (new column): the cleanup can see how long a row has been waiting.
 *  plus the FAILED / FAILED_ALL rows of SixExportRunGuard. Rows are no longer deleted during the export (the
 *  nightly cleanup empties the table), so the state of every list of every job is visible in the table.
 *  The XML claim (SELECT ... FOR UPDATE SKIP LOCKED + BUILDING in one transaction) guarantees that only one server
 *  builds the file of a list.
 */
@Component
@ReglissBatchProfile
@Slf4j
public class SixFilteredStore {

    private static final Pattern SQL_NAME = Pattern.compile("^[A-Z][A-Z0-9_]{0,127}$");
    private static final int DELETE_CHUNK = 5000;
    private static final int IN_LIST_LIMIT = 1000;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private NamedParameterJdbcTemplate namedJdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate newTx;

    @PostConstruct
    void init() {
        newTx = new TransactionTemplate(transactionManager);
        newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------------------------------------ filtered rows

    /** Removes the filtered rows of one raw version for one output list (export re-run / regeneration / failure). */
    public int deleteVersionOfList(SixFileKind kind, long versionId, String listRef) {
        String sql = "DELETE FROM " + filteredTable(kind) + " WHERE VERSION_ID = ? AND SIX_LIST_REF = ? AND ROWNUM <= ?";
        return deleteInChunks(sql, versionId, listRef);
    }

    private int deleteInChunks(String sql, long versionId, String listRef) {
        int total = 0;
        int deleted;
        do {
            Integer n = newTx.execute(status -> jdbcTemplate.update(sql, versionId, listRef, DELETE_CHUNK));
            deleted = n == null ? 0 : n;
            total += deleted;
        } while (deleted == DELETE_CHUNK);
        return total;
    }

    /** Table name from the SixFileKind constant, checked against a strict identifier pattern (Fortify). */
    private static String filteredTable(SixFileKind kind) {
        String table = kind.getFilteredTable();
        if (table == null || !SQL_NAME.matcher(table).matches()) {
            throw new IllegalStateException("Unsupported filtered table for " + kind + ": " + table);
        }
        return table;
    }

    // ------------------------------------------------------------------------------------------ poller rows

    /** STATUS of a list row of SIX_FILTERED_POLLER (FILE_TYPE stays the plain type: INSTR, STRUCT, OPTIONS). */
    public static final String PENDING = "PENDING";
    public static final String READY = "READY";
    public static final String BUILDING = "BUILDING";
    public static final String DONE = "DONE";
    public static final String DROPPED = "DROPPED";

    private static final String COLUMNS = "SELECT ID, FILE_TYPE, RAW_LIST_ID, RAW_VERSION_ID, BATCH_JOB_EXECUTION_ID FROM SIX_FILTERED_POLLER";
    private static final String READY_ROWS = COLUMNS + " WHERE SIX_LIST_REFERENCE = ? AND STATUS = '" + READY + "'";
    private static final String LIST_STATUSES = "('" + String.join("', '", PENDING, READY, BUILDING, DONE, DROPPED) + "')";

    /** PENDING -> READY: the list is filtered and written, the XML step can use it. */
    public void announce(long pollerRowId) {
        changeStatus(pollerRowId, PENDING, READY);
    }

    /** PENDING -> DROPPED: the list is not filtered by this export (it failed here, or failed on the other server). */
    public void dropPending(long pollerRowId) {
        changeStatus(pollerRowId, PENDING, DROPPED);
    }

    private void changeStatus(long pollerRowId, String from, String to) {
        int n = jdbcTemplate.update("UPDATE SIX_FILTERED_POLLER SET STATUS = ?, UPDATED_TIME = ? WHERE ID = ? AND STATUS = ?",
                to, now(), pollerRowId, from);
        if (n != 1) {
            throw new IllegalStateException("SIX_FILTERED_POLLER row " + pollerRowId + " is not " + from + " any more");
        }
    }

    /**
     * PENDING rows of a job -> DROPPED: the export of the delivery is stopped, these lists will not be filtered.
     * Lists already written (READY) are kept: when every file type of a list is ready, its file is still built.
     */
    public int dropPendingRowsOfJob(long batchJobExecutionId) {
        return jdbcTemplate.update("UPDATE SIX_FILTERED_POLLER SET STATUS = ?, UPDATED_TIME = ? WHERE BATCH_JOB_EXECUTION_ID = ? AND STATUS = ?",
                DROPPED, now(), batchJobExecutionId, PENDING);
    }

    /** READY rows of one list (no lock). */
    public List<PollerRow> readyRows(String listRef) {
        return jdbcTemplate.query(READY_ROWS, SixFilteredStore::pollerRow, listRef);
    }

    /** Jobs that still have to filter this list (PENDING rows): the list is waiting for them. */
    public List<Long> jobsStillFiltering(String listRef) {
        return jdbcTemplate.queryForList("SELECT DISTINCT BATCH_JOB_EXECUTION_ID FROM SIX_FILTERED_POLLER WHERE SIX_LIST_REFERENCE = ?"
                + " AND STATUS = ? AND BATCH_JOB_EXECUTION_ID IS NOT NULL", Long.class, listRef, PENDING);
    }

    /** READY -> DROPPED (their list failed): they never become a file. */
    public void dropReady(List<Long> ids) {
        updateIds(READY, DROPPED, ids);
    }

    /** BUILDING -> DONE: the CONVERTER file of the list is in the DJ IN folder. */
    public void markBuilt(List<Long> ids) {
        updateIds(BUILDING, DONE, ids);
    }

    /** BUILDING -> DROPPED: the CONVERTER file of the list could not be generated. */
    public void markBuildFailed(List<Long> ids) {
        updateIds(BUILDING, DROPPED, ids);
    }

    private void updateIds(String from, String to, List<Long> ids) {
        for (int start = 0; start < ids.size(); start += IN_LIST_LIMIT) {
            MapSqlParameterSource params = new MapSqlParameterSource("ids", ids.subList(start, Math.min(start + IN_LIST_LIMIT, ids.size())))
                    .addValue("fromStatus", from).addValue("toStatus", to).addValue("updatedTime", now());
            namedJdbcTemplate.update("UPDATE SIX_FILTERED_POLLER SET STATUS = :toStatus, UPDATED_TIME = :updatedTime"
                    + " WHERE ID IN (:ids) AND STATUS = :fromStatus", params);
        }
    }

    /**
     * Claims the READY rows of one output list when every required file type is there: they become BUILDING in the
     * same transaction (SELECT ... FOR UPDATE SKIP LOCKED), so only ONE server builds the file of a list.
     *
     * @param requiredTypes the file types of allow.six.file.integration (upper case), e.g. INSTR, STRUCT
     * @return claimed = true: the rows are BUILDING and belong to this caller only;
     *         claimed = false: nothing changed, the visible READY rows are returned
     */
    public PollerClaim claimPollerRows(String listRef, Set<String> requiredTypes) {
        PollerClaim claim = newTx.execute(status -> {
            List<PollerRow> rows = jdbcTemplate.query(READY_ROWS + " FOR UPDATE SKIP LOCKED", SixFilteredStore::pollerRow, listRef);
            Set<String> types = rows.stream().map(PollerRow::getFileType).filter(Objects::nonNull).collect(Collectors.toSet());
            if (rows.isEmpty() || !types.containsAll(requiredTypes)) {
                return new PollerClaim(false, rows);
            }
            updateIds(READY, BUILDING, rows.stream().map(PollerRow::getId).collect(Collectors.toList()));
            return new PollerClaim(true, rows);
        });
        return claim == null ? new PollerClaim(false, Collections.emptyList()) : claim;
    }

    /** List rows of a job by STATUS (the FAILED / FAILED_ALL signal rows are not list rows). */
    public JobLists jobLists(long batchJobExecutionId) {
        List<String> statuses = jdbcTemplate.queryForList("SELECT STATUS FROM SIX_FILTERED_POLLER WHERE BATCH_JOB_EXECUTION_ID = ?"
                + " AND STATUS IN " + LIST_STATUSES, String.class, batchJobExecutionId);
        return new JobLists(statuses);
    }

    /**
     * UPDATED_TIME of a status change: the application clock, like INSERTION_TIME (set by the entity), so the two
     * columns can be compared (e.g. by the cleanup: "PENDING for more than N hours").
     */
    private static Timestamp now() {
        return Timestamp.valueOf(LocalDateTime.now());
    }

    private static PollerRow pollerRow(ResultSet rs, int rowNum) throws SQLException {
        return new PollerRow(rs.getLong("ID"), rs.getString("FILE_TYPE"), nullableLong(rs, "RAW_LIST_ID"),
                nullableLong(rs, "RAW_VERSION_ID"), nullableLong(rs, "BATCH_JOB_EXECUTION_ID"));
    }

    /** The lists of one job: how many, how many with their file, how many still open, how many dropped. */
    public static final class JobLists {
        private final int total;
        private final int done;
        private final int open;
        private final int dropped;

        JobLists(List<String> statuses) {
            this.total = statuses.size();
            this.done = (int) statuses.stream().filter(DONE::equals).count();
            this.dropped = (int) statuses.stream().filter(DROPPED::equals).count();
            this.open = total - done - dropped;
        }

        /** Number of lists of the job (its share of the progress is 90 % / total). */
        public int getTotal() { return total; }
        public int getDone() { return done; }
        /** PENDING, READY or BUILDING. */
        public int getOpen() { return open; }
        public int getDropped() { return dropped; }

        /** Every list has its file: the job can be set to 100 % with its end date. */
        public boolean allBuilt() {
            return total > 0 && done == total;
        }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    /** One SIX_FILTERED_POLLER row. */
    public static final class PollerRow {
        private final long id;
        private final String fileType;
        private final Long rawListId;
        private final Long rawVersionId;
        private final Long batchJobExecutionId;

        PollerRow(long id, String fileType, Long rawListId, Long rawVersionId, Long batchJobExecutionId) {
            this.id = id;
            this.fileType = fileType;
            this.rawListId = rawListId;
            this.rawVersionId = rawVersionId;
            this.batchJobExecutionId = batchJobExecutionId;
        }

        public long getId() { return id; }
        public String getFileType() { return fileType; }
        public Long getRawListId() { return rawListId; }
        public Long getRawVersionId() { return rawVersionId; }
        public Long getBatchJobExecutionId() { return batchJobExecutionId; }
    }

    /** Result of claimPollerRows. */
    public static final class PollerClaim {
        private final boolean claimed;
        private final List<PollerRow> rows;

        PollerClaim(boolean claimed, List<PollerRow> rows) {
            this.claimed = claimed;
            this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
        }

        public boolean isClaimed() { return claimed; }
        public List<PollerRow> getRows() { return rows; }

        /** Distinct, non-null BATCH_JOB_EXECUTION_IDs of the rows. */
        public Set<Long> jobIds() {
            return rows.stream().map(PollerRow::getBatchJobExecutionId).filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        @Override
        public String toString() {
            return rows.stream().map(r -> r.getFileType() + "(v" + r.getRawVersionId() + ")").collect(Collectors.joining(", "));
        }
    }
}
