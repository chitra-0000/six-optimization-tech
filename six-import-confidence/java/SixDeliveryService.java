package com.bnpp.regliss.importer.six.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.persistence.EntityManager;          // jakarta.persistence.* on Spring Boot 3
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;

// TODO: re-add project imports (Alt+Enter / Optimize Imports) for:
// ReglissBatchProfile, AutomaticFeedAggregator, AutomaticSixImportFileRepository, AutomaticImportFeedBase,
// BatchExportRepository, BatchExportService, BatchExport, BatchExportType, GenerationReason,
// BatchJobExecution, BatchJobType, BatchManagementService, BatchProgressService, UnblockImportService,
// ReglissListRepository, ReglissList, EmailService, Version, User, NodeDetailsSupplier

/**
 * A SIX "delivery" = the SIX files with the same date+time in their name (INSTR, STRUCT, OPT ...).
 * The delivery succeeds only if ALL its files succeed (all or nothing).
 *
 * State of a delivery is read from what already exists - no new table, no new folder:
 *   - the files in the IN folder (a file now stays in IN until its whole delivery is finished;
 *     its list stays locked meanwhile, so it is never imported twice),
 *   - the open BATCH_IMPORT_SIXRAW jobs (CTR_BATCH_JOB_EXECUTION),
 *   - the SIX_CONFIDENCE_VALUES rows (CTR_BATCH_EXPORT), one per imported file waiting at 90%.
 *
 * State of one file of the delivery:
 *   PENDING  file in IN, import not started
 *   RUNNING  import job open, not finished yet
 *   WAITING  imported, waiting at 90% for the confidence step (has a SIX_CONFIDENCE_VALUES row)
 *
 * Rule used everywhere: files are handled FIRST (deleted or moved to ERROR), the list is unlocked LAST.
 * So a file can never be in IN while its list is unlocked.
 */
@Slf4j
@Service
@ReglissBatchProfile
public class SixDeliveryService {

    private static final long KEEP_ALIVE_EVERY_MS = 60_000L;

    @Value("${six.file.prefix}")
    private String sixFileNamePrefix;

    @Autowired
    private AutomaticSixImportFileRepository sixFileRepo;
    @Autowired
    private BatchExportRepository batchExportRepository;
    @Autowired
    private BatchExportService batchExportService;
    @Autowired
    private BatchManagementService batchManagementService;
    @Autowired
    private BatchProgressService batchProgressService;
    @Autowired
    private UnblockImportService unblockImportService;
    @Autowired
    private ReglissListRepository reglissListRepository;
    @Autowired
    private EmailService emailService;
    @Autowired
    private NodeDetailsSupplier nodeDetailsSupplier;
    @Autowired
    private SixConfidenceStore store;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private final AtomicLong lastKeepAlive = new AtomicLong();

    // =====================================================================================
    // Delivery key
    // =====================================================================================

    /** Date+time of a SIX file name, computed exactly like AutomaticFeedAggregator (null if not a SIX raw name). */
    public static Long deliveryKey(String fileName) {
        if (fileName == null) {
            return null;
        }
        Matcher m = AutomaticFeedAggregator.SIX_RAW_PATTERN.matcher(FilenameUtils.getBaseName(fileName).toUpperCase());
        if (!m.matches()) {
            return null;
        }
        try {
            return Long.parseLong(m.group(2) + m.group(3));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // =====================================================================================
    // Called by AutomaticFeedImporter at the end of each SIX import
    // =====================================================================================

    /**
     * After a successful import. The file is NOT deleted any more: it stays in IN until the whole
     * delivery is finished (the list lock prevents a second import).
     *
     * If the file is no longer in IN, another file of the same delivery failed meanwhile (possibly on
     * the other server) and moved the delivery to ERROR -> this file is rolled back too.
     *
     * @return true if the file now waits for the other files, false if it was rolled back.
     */
    public boolean afterSuccessfulImport(AutomaticImportFeedBase feed) {
        String fileName = feed.getXmlDataFileName();
        if (sixFileRepo.getInputFileByName(fileName).exists()) {
            log.info("SIX {} imported, waiting for the other files of its delivery", fileName);
            return true;
        }
        log.warn("SIX {} imported, but its delivery was cancelled meanwhile (another file failed) -> rolling it back", fileName);
        rollbackDelivery(deliveryKey(fileName), null, "another file of the same delivery failed");
        return false;
    }

    /** After a failed import: the whole delivery is rolled back and all its files go to ERROR. */
    public void afterFailedImport(AutomaticImportFeedBase feed, Long listId, Long failedJobId, Exception error) {
        String fileName = feed.getXmlDataFileName();
        rollbackDelivery(deliveryKey(fileName), failedJobId, "file " + fileName + " failed: " + error.getMessage());
        if (failedJobId == null && listId != null) {
            // failed before its job was created: only the list lock (taken by AutomaticImporter) has to be released
            batchManagementService.attemptUnlockListWithCurrentNode(listId);
        }
    }

    // =====================================================================================
    // Rollback (all or nothing)
    // =====================================================================================

    /**
     * Rolls back a whole delivery. Safe to run twice and on both servers at the same time
     * (deleting a deleted row/version does nothing, moving a file that is already gone is skipped).
     *
     *  1. every file of the delivery still in IN -> ERROR (files not started yet are therefore never imported)
     *  2. every WAITING file: confidence row deleted, version + records deleted, job closed, list unlocked, mail
     *  3. the failed job (its version is already deleted by ImportService): closed, list unlocked
     *
     * A file still RUNNING (other server) is not touched here: when its import ends it sees that its
     * file left IN and rolls itself back (afterSuccessfulImport) - nobody deletes rows while they are written.
     */
    public void rollbackDelivery(Long key, Long failedJobId, String reason) {
        log.error("SIX delivery {} is rolled back: {}", key, reason);
        if (key != null) {
            moveDeliveryFilesToError(key);
            rollbackWaitingFiles(key, reason);
        }
        if (failedJobId != null) {
            closeJob(failedJobId, User.SYS_USERNAME);
        }
    }

    /** Step 1: every file of the delivery still in IN goes to ERROR (so files not started yet are never imported). */
    private void moveDeliveryFilesToError(Long key) {
        for (String fileName : filesInInputFolder(key).values()) {
            try {
                if (sixFileRepo.moveToErrorDirectoryIfPresent(fileName)) {
                    log.info("SIX {} moved to the error folder", fileName);
                }
            } catch (RuntimeException e) {
                log.error("SIX delivery {}: could not move {} to the error folder", key, fileName, e);
            }
        }
    }

    /** Step 2: every file of the delivery waiting at 90% is rolled back. */
    private void rollbackWaitingFiles(Long key, String reason) {
        for (WaitingRow row : waitingRows()) {
            if (!key.equals(row.key)) {
                continue;
            }
            try {
                rollbackWaitingFile(row, key, reason);
            } catch (RuntimeException e) {
                // the row is still there: the confidence poller sees the cancelled delivery and retries the rollback
                log.error("SIX delivery {}: rollback of {} failed, will be retried", key, row, e);
            }
        }
    }

    private void rollbackWaitingFile(WaitingRow row, Long key, String reason) {
        log.warn("SIX delivery {}: rolling back {} (version {})", key, row.kind, row.versionId);
        store.deleteRow(row.exportId);                       // first: no longer "waiting", the poller cannot pick it
        if (row.versionId != null) {
            unblockImportService.tryDeleteSixAutomaticVersionAndRecordsInTx(row.versionId);
        }
        closeJob(row.jobId, row.launcher);
        try {
            BatchJobExecution job = entityManager.find(BatchJobExecution.class, row.jobId);
            if (job != null && job.getReglissListId() != null) {
                ReglissList list = reglissListRepository.getExactlyOne(job.getReglissListId());
                emailService.sendEmailDjImportGeneralError(list, "SIX delivery " + key + " rolled back ("
                        + reason + "). This file was imported successfully but is removed because the delivery is all or nothing; "
                        + "all files of the delivery were moved to the error folder.");
            }
        } catch (Exception e) {
            log.error("Could not send the rollback mail for job {}", row.jobId, e);
        }
    }

    // =====================================================================================
    // Delivery state (used by the confidence poller)
    // =====================================================================================

    /**
     * The delivery to process, with the state of each of its files.
     *
     * It is the LATEST delivery (date+time of the SIX file names) that has files waiting at 90%: a new delivery is only
     * imported when the previous one is finished, so a row of an OLDER delivery - or a second row of the same file type
     * in the latest delivery (the lower version) - is a leftover. Leftovers are superseded: row deleted (never merged,
     * never exported), the older delivery's files still in IN moved to IGNORE, job closed and list unlocked. No mail.
     * Exception: a delivery already in its last step (instrument row taken) is finished first.
     */
    public Optional<Delivery> findWaitingDelivery() {
        List<WaitingRow> rows = waitingRows();
        Optional<Long> finishingKey = rows.stream()
                .filter(r -> r.key != null && r.kind != null && r.kind.isSource() && r.batchNodeId != null)
                .map(r -> r.key).max(Long::compare);
        Optional<Long> latestKey = finishingKey.isPresent() ? finishingKey
                : rows.stream().map(r -> r.key).filter(Objects::nonNull).max(Long::compare);
        if (!latestKey.isPresent()) {
            if (!rows.isEmpty()) {
                log.warn("SIX_CONFIDENCE_VALUES rows found whose job has no SIX file name: {}", rows);
            }
            return Optional.empty();
        }
        if (!finishingKey.isPresent()) {
            rows = supersedeLeftovers(rows, latestKey.get());
        }
        Long key = latestKey.get();
        Delivery delivery = new Delivery(key);

        for (WaitingRow row : rows) {
            if (key.equals(row.key) && row.kind != null) {
                delivery.file(row.kind).row = row;
                delivery.file(row.kind).jobId = row.jobId;
            }
        }
        for (BatchJobExecution job : openSixImportJobs()) {
            if (key.equals(store.deliveryKeyOfJob(job.getId()))) {
                kindOfList(job.getReglissListId()).ifPresent(kind -> {
                    delivery.file(kind).jobOpen = true;
                    if (delivery.file(kind).jobId == null) {
                        delivery.file(kind).jobId = job.getId();
                    }
                });
            }
        }
        filesInInputFolder(key).forEach((kind, fileName) -> delivery.file(kind).fileInInput = fileName);
        return Optional.of(delivery);
    }

    /**
     * Supersedes the leftover rows (see findWaitingDelivery) and returns the rows that are kept: the newest row of each
     * file type of the latest delivery (highest version), and the rows without delivery key (only logged, as before).
     */
    private List<WaitingRow> supersedeLeftovers(List<WaitingRow> rows, Long latestKey) {
        Map<SixFileKind, WaitingRow> newestByKind = new EnumMap<>(SixFileKind.class);
        for (WaitingRow row : rows) {
            if (latestKey.equals(row.key) && row.kind != null) {
                newestByKind.merge(row.kind, row, (a, b) -> versionOf(a) >= versionOf(b) ? a : b);
            }
        }
        List<WaitingRow> kept = new ArrayList<>();
        Map<Long, List<WaitingRow>> olderDeliveries = new TreeMap<>();
        for (WaitingRow row : rows) {
            if (row.key == null || (latestKey.equals(row.key) && (row.kind == null || newestByKind.get(row.kind) == row))) {
                kept.add(row);
            } else if (latestKey.equals(row.key)) {
                supersedeRows(Collections.singletonList(row), latestKey);     // second row of the same type: lower version
            } else {
                olderDeliveries.computeIfAbsent(row.key, k -> new ArrayList<>()).add(row);
            }
        }
        olderDeliveries.forEach((olderKey, olderRows) -> supersedeOlderDelivery(olderKey, olderRows, latestKey));
        return kept;
    }

    /** Rows of an older delivery: rows deleted, its files still in IN moved to IGNORE (except a file being imported), jobs closed. */
    private void supersedeOlderDelivery(Long olderKey, List<WaitingRow> olderRows, Long latestKey) {
        try {
            for (WaitingRow row : olderRows) {
                store.deleteRow(row.exportId);                     // first: never picked again
            }
            Set<SixFileKind> importing = new HashSet<>();
            for (BatchJobExecution job : openSixImportJobs()) {
                if (olderKey.equals(store.deliveryKeyOfJob(job.getId())) && olderRows.stream().noneMatch(r -> r.jobId == job.getId())) {
                    kindOfList(job.getReglissListId()).ifPresent(importing::add);
                }
            }
            filesInInputFolder(olderKey).forEach((kind, fileName) -> {
                if (!importing.contains(kind) && sixFileRepo.moveToIgnoreDirectoryIfPresent(fileName)) {
                    log.info("SIX {} of the superseded delivery {} moved to the ignore folder", fileName, olderKey);
                }
            });
        } catch (RuntimeException e) {
            log.error("SIX delivery {}: could not supersede it completely, retried at the next tick", olderKey, e);
            return;
        }
        supersedeRows(olderRows, latestKey);
    }

    /** Deletes the rows (again: harmless) and closes their jobs, which unlocks their lists. Logged, no mail. */
    private void supersedeRows(List<WaitingRow> superseded, Long latestKey) {
        for (WaitingRow row : superseded) {
            try {
                log.warn("SIX delivery {}: {} (version {}, job {}) superseded by delivery {} - not merged, not exported",
                        row.key, row.kind, row.versionId, row.jobId, latestKey);
                store.deleteRow(row.exportId);
                closeJob(row.jobId, row.launcher);                 // unlock last
            } catch (RuntimeException e) {
                log.error("SIX: could not supersede {}, retried at the next tick", row, e);
            }
        }
    }

    private static long versionOf(WaitingRow row) {
        return row.versionId == null ? Long.MIN_VALUE : row.versionId;
    }

    /** Last step of a finished delivery, for one file. Order chosen so a crash at any point can be resumed. */
    public void finishFile(Delivery delivery, DeliveryFile file) {
        // 1. files first
        if (file.fileInInput != null) {
            sixFileRepo.deleteInputFile(file.fileInInput);
        }
        if (file.jobOpen) {
            batchManagementService.updateBatchExecutionCompletedProgress(file.jobId);
        }
        if (file.kind.isSource()) {
            // The instrument row marks "delivery finishing": it is deleted LAST so an interrupted finish can be resumed.
            closeJob(file.jobId, launcherOf(file));
            createFilteredExportAndDeleteRow(file);
        } else {
            createFilteredExportAndDeleteRow(file);
            closeJob(file.jobId, launcherOf(file));     // 2. unlock last
        }
        log.info("SIX delivery {}: {} finished (100%)", delivery.key, file.kind);
    }

    /** Part 3 is triggered exactly once per file: the filtered-export row and the deletion of the confidence row are one transaction. */
    private void createFilteredExportAndDeleteRow(DeliveryFile file) {
        if (file.row == null) {
            return;     // already done before an interruption
        }
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.execute(status -> {
            batchExportService.persistSixFilteredFileGenerationStubInTx(BatchExportType.SIX_FILTERED_FILE_GENERATION,
                    GenerationReason.GENERATION, file.row.version);
            store.deleteRow(file.row.exportId);
            return null;
        });
    }

    // =====================================================================================
    // Keep-alive: files waiting at 90% must not be reported as stuck while the delivery is progressing
    // =====================================================================================

    /**
     * Refreshes lastUpdateDate (percent + 0) of the files waiting at 90%, using the existing
     * BatchProgressService.incrementExportBatchForSix. Called by a file that is actively progressing
     * (import batches, confidence merge), at most once a minute per server.
     *
     * Because only a progressing file calls it, a delivery whose running file is really stuck
     * (or whose server died) stops refreshing -> the existing 30-minute check still reports it.
     */
    public void keepWaitingFilesAlive(long callerJobId) {
        long now = System.currentTimeMillis();
        long last = lastKeepAlive.get();
        if (now - last < KEEP_ALIVE_EVERY_MS || !lastKeepAlive.compareAndSet(last, now)) {
            return;
        }
        try {
            for (WaitingRow row : waitingRows()) {
                if (row.jobId != callerJobId) {
                    batchProgressService.incrementExportBatchForSix(row.jobId, 0);
                }
            }
        } catch (Exception e) {
            log.warn("SIX keep-alive of the waiting files failed (ignored): {}", e.getMessage());
        }
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================

    public String currentNodeId() {
        return nodeDetailsSupplier.getNodeId();
    }

    private void closeJob(Long jobId, String launcherUserName) {
        if (jobId == null) {
            return;
        }
        try {
            // existing method: end date + lastUpdateDate + unblocking user, then removes the list lock of the job's node
            unblockImportService.finishSixImportExecutionByRequesterUser(jobId, launcherUserName);
        } catch (Exception e) {
            log.error("Could not close SIX job {} / unlock its list", jobId, e);
        }
    }

    private static String launcherOf(DeliveryFile file) {
        return file.row != null && file.row.launcher != null ? file.row.launcher : User.SYS_USERNAME;
    }

    private List<WaitingRow> waitingRows() {
        List<WaitingRow> result = new ArrayList<>();
        for (BatchExport export : batchExportRepository.getByBatchType()) {
            List<Long> ids = export.getExportParams().getGeneratedFileIds();
            if (ids == null || ids.isEmpty()) {
                log.warn("SIX_CONFIDENCE_VALUES row {} has no job id", export.getId());
                continue;
            }
            WaitingRow row = new WaitingRow();
            row.exportId = export.getId();
            row.jobId = ids.get(0);
            row.batchNodeId = store.currentNodeOfRow(export.getId());   // fresh value, not the JPA-cached entity
            row.launcher = export.getExportParams().getLauncherUsername();
            row.version = export.getExportParams().getVersionOpt().orElse(null);
            row.versionId = row.version == null ? null : row.version.getId();
            row.kind = row.version == null ? null : SixFileKind.of(row.version.getImportFileType()).orElse(null);
            row.key = store.deliveryKeyOfJob(row.jobId);
            result.add(row);
        }
        return result;
    }

    private List<BatchJobExecution> openSixImportJobs() {
        return entityManager.createQuery(
                        "SELECT e FROM BatchJobExecution e WHERE e.jobType = :type AND e.endDate IS NULL", BatchJobExecution.class)
                .setParameter("type", BatchJobType.BATCH_IMPORT_SIXRAW)
                .getResultList();
    }

    private Optional<SixFileKind> kindOfList(Long listId) {
        if (listId == null) {
            return Optional.empty();
        }
        return SixFileKind.of(reglissListRepository.getExactlyOne(listId).getImportFileType());
    }

    /** SIX files of one delivery currently in the IN folder, by kind. */
    private Map<SixFileKind, String> filesInInputFolder(Long key) {
        Map<SixFileKind, String> files = new EnumMap<>(SixFileKind.class);
        // Plain directory listing (not getFilesInInputFolder): a file still being written by CFT also belongs to the delivery.
        File[] inInput = sixFileRepo.getInputDirectory().listFiles(File::isFile);
        if (inInput == null) {
            throw new IllegalStateException("Could not list the SIX IN folder " + sixFileRepo.getInputDirectory());
        }
        for (File file : inInput) {
            String fileName = file.getName();
            if (fileName.startsWith(sixFileNamePrefix) && key.equals(deliveryKey(fileName))) {
                SixFileKind.ofFileName(fileName).ifPresent(kind -> files.putIfAbsent(kind, fileName));
            }
        }
        return files;
    }

    // =====================================================================================
    // Value classes
    // =====================================================================================

    /** One SIX_CONFIDENCE_VALUES row = one imported file waiting at 90%. */
    public static final class WaitingRow {
        long exportId;
        long jobId;
        Long key;
        SixFileKind kind;
        Version version;
        Long versionId;
        String batchNodeId;      // NULL = confidence still to merge (dependent) / delivery not finishing (instrument)
        String launcher;

        public Long getVersionId() { return versionId; }
        public long getExportId() { return exportId; }
        public String getBatchNodeId() { return batchNodeId; }

        @Override
        public String toString() {
            return "{export " + exportId + ", job " + jobId + ", " + kind + ", key " + key + "}";
        }
    }

    public enum FileState { PENDING, RUNNING, WAITING, ABSENT }

    /** One file of a delivery. */
    public static final class DeliveryFile {
        final SixFileKind kind;
        WaitingRow row;
        Long jobId;
        boolean jobOpen;
        String fileInInput;

        DeliveryFile(SixFileKind kind) { this.kind = kind; }

        public SixFileKind getKind() { return kind; }
        public WaitingRow getRow() { return row; }
        public Long getJobId() { return jobId; }

        public FileState state() {
            if (row != null) return FileState.WAITING;
            if (jobOpen) return FileState.RUNNING;
            if (fileInInput != null) return FileState.PENDING;
            return FileState.ABSENT;
        }

        /** Merged already (or does not need a merge). */
        public boolean confidenceDone() {
            return !kind.receivesConfidence() || (row != null && row.batchNodeId != null);
        }

        @Override
        public String toString() { return kind + "=" + state(); }
    }

    public static final class Delivery {
        final Long key;
        private final Map<SixFileKind, DeliveryFile> files = new EnumMap<>(SixFileKind.class);

        Delivery(Long key) { this.key = key; }

        public Long getKey() { return key; }

        DeliveryFile file(SixFileKind kind) {
            return files.computeIfAbsent(kind, DeliveryFile::new);
        }

        /** Files that belong to the delivery (ABSENT ones excluded). */
        public List<DeliveryFile> members() {
            List<DeliveryFile> result = new ArrayList<>();
            for (DeliveryFile f : files.values()) {
                if (f.state() != FileState.ABSENT) result.add(f);
            }
            return Collections.unmodifiableList(result);
        }

        public Optional<DeliveryFile> instrument() {
            return Optional.ofNullable(files.get(SixFileKind.source())).filter(f -> f.state() != FileState.ABSENT);
        }

        /** The instrument row is taken by a server: the delivery is in its last step. */
        public boolean isFinishing() {
            return instrument().map(f -> f.row != null && f.row.batchNodeId != null).orElse(false);
        }

        public boolean hasPendingOrRunning() {
            return members().stream().anyMatch(f -> f.state() == FileState.PENDING || f.state() == FileState.RUNNING);
        }

        /** A waiting file whose file left IN: the delivery was cancelled by a rollback. */
        public boolean isCancelled() {
            return members().stream().anyMatch(f -> f.state() == FileState.WAITING && f.fileInInput == null);
        }

        @Override
        public String toString() { return "SIX delivery " + key + " " + members(); }
    }
}
