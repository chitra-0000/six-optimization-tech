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
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
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
 *  OPTIONS (unchanged), STATUS:
 *    PENDING -> READY -> BUILDING -> BUILT -> DONE
 *       \-> DROPPED (list failed / skipped / export stopped / job dead)
 *                              \-> DROPPED (XML failed)   \-> DROPPED (move to DJ IN failed)
 *  GENERATION_REASON (export phase 1): GENERATION / REGENERATION; the two are claimed, built and released separately.
 *  BUILT (export phase 1): the CONVERTER file is in the holding folder and waits for the other lists of its delivery;
 *  DONE: the file is in the DJ IN folder. All files of a delivery are moved together (SixXmlGenerationPoller).
 *  Every status change also sets UPDATED_TIME; a BUILDING row is also touched while its file is being built, so a
 *  build that stopped (server crash) is recognised by an old UPDATED_TIME.
 *  Plus the FAILED / FAILED_ALL rows of SixExportRunGuard. Rows are not deleted during the export (the nightly cleanup
 *  empties the table), so the state of every list of every job is visible in the table.
 *  The XML claim and the release (SELECT ... FOR UPDATE SKIP LOCKED + status change in one transaction) guarantee that
 *  only one server builds the file of a list and only one server moves the files of a delivery.
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

    /**
     * The filtered rows of a DROPPED list row (its list failed, or its job stopped): they will never become a file,
     * so they are removed now instead of waiting for the nightly cleanup. Kept when another row of the same list and
     * raw version is still in use (e.g. a regeneration of that list is writing the same version again).
     *
     * @return the number of rows removed, -1 when they are kept
     */
    public int removeFilteredRowsOfDroppedRow(PollerRow row) {
        Optional<SixFileKind> kind = kindOfPollerType(row.getFileType());
        if (!kind.isPresent() || row.getRawVersionId() == null || row.getListRef() == null) {
            return -1;
        }
        Integer inUse = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SIX_FILTERED_POLLER WHERE SIX_LIST_REFERENCE = ?"
                + " AND RAW_VERSION_ID = ? AND FILE_TYPE = ? AND STATUS IN " + IN_USE_STATUSES, Integer.class,
                row.getListRef(), row.getRawVersionId(), row.getFileType());
        if (inUse != null && inUse > 0) {
            return -1;
        }
        return deleteVersionOfList(kind.get(), row.getRawVersionId(), row.getListRef());
    }

    private static Optional<SixFileKind> kindOfPollerType(String pollerFileType) {
        return Arrays.stream(SixFileKind.values()).filter(k -> k.getPollerFileType().equals(pollerFileType)).findFirst();
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
    /** The CONVERTER file is in the holding folder, waiting for the other lists of its delivery. */
    public static final String BUILT = "BUILT";
    /** The CONVERTER file is in the DJ IN folder. */
    public static final String DONE = "DONE";
    public static final String DROPPED = "DROPPED";

    private static final String COLUMNS = "SELECT ID, FILE_TYPE, RAW_LIST_ID, RAW_VERSION_ID, BATCH_JOB_EXECUTION_ID,"
            + " SIX_LIST_REFERENCE, STATUS, UPDATED_TIME, GENERATION_REASON FROM SIX_FILTERED_POLLER";
    /** GENERATION_REASON, NULL (rows of older code) read as GENERATION. */
    private static final String REASON = "COALESCE(GENERATION_REASON, '" + SixExportRunGuard.GENERATION + "')";
    private static final String READY_ROWS = COLUMNS + " WHERE SIX_LIST_REFERENCE = ? AND STATUS = '" + READY + "'";
    private static final String LIST_STATUSES = inList(PENDING, READY, BUILDING, BUILT, DONE, DROPPED);
    private static final String IN_USE_STATUSES = inList(PENDING, READY, BUILDING, BUILT);

    private static String inList(String... statuses) {
        return "('" + String.join("', '", statuses) + "')";
    }

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

    /** List rows in one of the given states (PENDING, READY, BUILDING, BUILT: a few rows per running export). */
    public List<PollerRow> rowsInStatus(String... statuses) {
        return namedJdbcTemplate.query(COLUMNS + " WHERE STATUS IN (:statuses)",
                new MapSqlParameterSource("statuses", Arrays.asList(statuses)), SixFilteredStore::pollerRow);
    }

    /** Jobs of the same reason (GENERATION / REGENERATION) that still have to filter this list (PENDING rows). */
    public List<Long> jobsStillFiltering(String listRef, String reason) {
        return jdbcTemplate.queryForList("SELECT DISTINCT BATCH_JOB_EXECUTION_ID FROM SIX_FILTERED_POLLER WHERE SIX_LIST_REFERENCE = ?"
                + " AND STATUS = ? AND BATCH_JOB_EXECUTION_ID IS NOT NULL AND " + REASON + " = ?", Long.class, listRef, PENDING, reason);
    }

    /** Rows of one list and raw version still in work (PENDING, READY, BUILDING), any reason. */
    public List<PollerRow> openRowsOfList(String listRef, long rawVersionId) {
        return jdbcTemplate.query(COLUMNS + " WHERE SIX_LIST_REFERENCE = ? AND RAW_VERSION_ID = ? AND STATUS IN "
                + inList(PENDING, READY, BUILDING), SixFilteredStore::pollerRow, listRef, rawVersionId);
    }

    /** READY -> DROPPED (their list failed): they never become a file. */
    public void dropReady(List<Long> ids) {
        updateIds(READY, DROPPED, ids);
    }

    /** BUILDING -> BUILT: the CONVERTER file of the list is in the holding folder, waiting for its delivery. */
    public void markBuilt(List<Long> ids) {
        updateIds(BUILDING, BUILT, ids);
    }

    /** BUILDING -> DROPPED: the CONVERTER file of the list could not be generated. */
    public void markBuildFailed(List<Long> ids) {
        updateIds(BUILDING, DROPPED, ids);
    }

    /** BUILT -> DONE: the CONVERTER file of the list is in the DJ IN folder. */
    public void markReleased(List<Long> ids) {
        updateIds(BUILT, DONE, ids);
    }

    /** BUILT -> DROPPED: the CONVERTER file could not be moved to the DJ IN folder. */
    public void markReleaseFailed(List<Long> ids) {
        updateIds(BUILT, DROPPED, ids);
    }

    /** PENDING / READY / BUILDING -> DROPPED: rows whose job stopped (server crash ...): they will never be finished. */
    public void dropUnfinished(List<Long> ids) {
        for (String from : Arrays.asList(PENDING, READY, BUILDING)) {
            updateIds(from, DROPPED, ids);
        }
    }

    /** UPDATED_TIME of BUILDING rows refreshed while their file is being built (shows the build is alive). */
    public void touchBuilding(List<Long> ids) {
        updateIds(BUILDING, BUILDING, ids);
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
     * Export phase 1: per GENERATION_REASON - the automatic export (GENERATION) and a regeneration of the same list
     * are two different files; the rows of one are never mixed with the rows of the other.
     *
     * @param requiredTypes the file types of allow.six.file.integration (upper case), e.g. INSTR, STRUCT
     * @return claimed = true: the rows (one reason) are BUILDING and belong to this caller only;
     *         claimed = false: nothing changed, the visible READY rows are returned
     */
    public PollerClaim claimPollerRows(String listRef, Set<String> requiredTypes) {
        PollerClaim claim = newTx.execute(status -> {
            List<PollerRow> rows = jdbcTemplate.query(READY_ROWS + " FOR UPDATE SKIP LOCKED", SixFilteredStore::pollerRow, listRef);
            Map<String, List<PollerRow>> byReason = rows.stream()
                    .collect(Collectors.groupingBy(PollerRow::getGenerationReason, TreeMap::new, Collectors.toList()));
            for (List<PollerRow> reasonRows : byReason.values()) {
                Set<String> types = reasonRows.stream().map(PollerRow::getFileType).filter(Objects::nonNull).collect(Collectors.toSet());
                if (types.containsAll(requiredTypes)) {
                    updateIds(READY, BUILDING, ids(reasonRows));
                    return new PollerClaim(true, reasonRows);
                }
            }
            return new PollerClaim(false, rows);
        });
        return claim == null ? new PollerClaim(false, Collections.emptyList()) : claim;
    }

    /**
     * Release of one delivery: locks its BUILT rows (SELECT ... FOR UPDATE SKIP LOCKED) and runs {@code release} in
     * the same transaction, so only ONE server moves the files of a delivery. The status changes made by
     * {@code release} (DONE / DROPPED) are committed together at the end.
     *
     * @param builtIds the BUILT rows of the delivery
     * @return the result of {@code release}; null when not every row could be locked (the other server is releasing
     *         this delivery right now, or a row changed meanwhile): nothing was done, the next run looks again
     */
    public <T> T releaseInTx(List<Long> builtIds, Function<List<PollerRow>, T> release) {
        if (builtIds.isEmpty() || builtIds.size() > IN_LIST_LIMIT) {
            throw new IllegalArgumentException("A delivery has 1 to " + IN_LIST_LIMIT + " list rows, not " + builtIds.size());
        }
        return newTx.execute(status -> {
            List<PollerRow> locked = namedJdbcTemplate.query(COLUMNS + " WHERE ID IN (:ids) AND STATUS = '" + BUILT
                    + "' FOR UPDATE SKIP LOCKED", new MapSqlParameterSource("ids", builtIds), SixFilteredStore::pollerRow);
            if (locked.size() != builtIds.size()) {
                log.debug("SIX release: {} of {} BUILT rows locked, released by the other server", locked.size(), builtIds.size());
                return null;
            }
            return release.apply(locked);
        });
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
        Timestamp updated = rs.getTimestamp("UPDATED_TIME");
        String reason = rs.getString("GENERATION_REASON");
        return new PollerRow(rs.getLong("ID"), rs.getString("FILE_TYPE"), nullableLong(rs, "RAW_LIST_ID"),
                nullableLong(rs, "RAW_VERSION_ID"), nullableLong(rs, "BATCH_JOB_EXECUTION_ID"),
                rs.getString("SIX_LIST_REFERENCE"), rs.getString("STATUS"), updated == null ? null : updated.toLocalDateTime(),
                reason == null ? SixExportRunGuard.GENERATION : reason);
    }

    /** The lists of one job: how many, how many in DJ IN, how many held, how many still open, how many dropped. */
    public static final class JobLists {
        private final int total;
        private final int done;
        private final int built;
        private final int open;
        private final int dropped;

        JobLists(List<String> statuses) {
            this.total = statuses.size();
            this.done = (int) statuses.stream().filter(DONE::equals).count();
            this.built = (int) statuses.stream().filter(BUILT::equals).count();
            this.dropped = (int) statuses.stream().filter(DROPPED::equals).count();
            this.open = total - done - built - dropped;
        }

        /** Number of lists of the job (its share of the progress is 90 % / total). */
        public int getTotal() { return total; }
        /** Lists whose file is in the DJ IN folder. */
        public int getDone() { return done; }
        /** Lists whose file is in the holding folder. */
        public int getBuilt() { return built; }
        /** PENDING, READY or BUILDING. */
        public int getOpen() { return open; }
        public int getDropped() { return dropped; }

        /** Every list has its file in the DJ IN folder: the job can be set to 100 % with its end date. */
        public boolean allDelivered() {
            return total > 0 && done == total;
        }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    /** Ids of rows. */
    public static List<Long> ids(Collection<PollerRow> rows) {
        return rows.stream().map(PollerRow::getId).collect(Collectors.toList());
    }

    /** One SIX_FILTERED_POLLER row. */
    public static final class PollerRow {
        private final long id;
        private final String fileType;
        private final Long rawListId;
        private final Long rawVersionId;
        private final Long batchJobExecutionId;
        private final String listRef;
        private final String status;
        private final LocalDateTime updatedTime;
        private final String generationReason;

        PollerRow(long id, String fileType, Long rawListId, Long rawVersionId, Long batchJobExecutionId, String listRef,
                  String status, LocalDateTime updatedTime, String generationReason) {
            this.id = id;
            this.fileType = fileType;
            this.rawListId = rawListId;
            this.rawVersionId = rawVersionId;
            this.batchJobExecutionId = batchJobExecutionId;
            this.listRef = listRef;
            this.status = status;
            this.updatedTime = updatedTime;
            this.generationReason = generationReason;
        }

        public long getId() { return id; }
        public String getFileType() { return fileType; }
        public Long getRawListId() { return rawListId; }
        public Long getRawVersionId() { return rawVersionId; }
        public Long getBatchJobExecutionId() { return batchJobExecutionId; }
        /** SIX_LIST_REFERENCE: the output list. */
        public String getListRef() { return listRef; }
        public String getStatus() { return status; }
        public LocalDateTime getUpdatedTime() { return updatedTime; }
        /** GENERATION or REGENERATION (NULL in the table = GENERATION). */
        public String getGenerationReason() { return generationReason; }

        @Override
        public String toString() {
            return "list " + listRef + " " + fileType + " " + status + " " + generationReason + " (row " + id + ", raw version " + rawVersionId
                    + ", job " + batchJobExecutionId + ")";
        }
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
            return jobIdsOf(rows);
        }

        @Override
        public String toString() {
            return rows.stream().map(r -> r.getFileType() + "(v" + r.getRawVersionId() + ")").collect(Collectors.joining(", "));
        }
    }

    /** Distinct, non-null BATCH_JOB_EXECUTION_IDs of rows. */
    public static Set<Long> jobIdsOf(Collection<PollerRow> rows) {
        return rows.stream().map(PollerRow::getBatchJobExecutionId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
