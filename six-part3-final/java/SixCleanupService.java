package com.bnpp.regliss.importer.six.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

// TODO: re-add project imports for: ReglissBatchProfile, BatchJobExecution, BatchJobExecutionRepository, BatchJobType,
// BatchExportRepository, VersionRepository, SixXmlGenerationService

/**
 * SIX part 4: nightly cleanup (once a day, SixCleanupPoller, one server). Runs only when NOTHING SIX is in progress,
 * otherwise it is skipped until the next day (no retry):
 *  - no working SIX import or SIX export job (BATCH_IMPORT_SIXRAW / BATCH_EXPORT_SIXRAW not finished, updated in the
 *    last 30 min, server heartbeat alive: SixExportRunGuard.isJobWorking). A job of a stopped server that was never
 *    closed does not block (logged);
 *  - no SIX_CONFIDENCE_VALUES row in CTR_BATCH_EXPORT (a delivery waiting for its confidence step);
 *  - no SIX_FILTERED_FILE_GENERATION request not yet taken by a server;
 *  - no SIX_FILTERED_POLLER list row in progress: BUILT (file waiting for its release), BUILDING refreshed in the last
 *    30 min, PENDING / READY of a working job.
 *
 * Steps, in this order (each one checks again that nothing started):
 *  1. Holding folder: every CONVERTER file left there (release failed, server stopped) is moved to DJ IN, unless DJ
 *     already received a newer file for that list (then it is removed, logged). ".tmp" files and empty folders removed.
 *  2. FILTERED_SIX_* emptied (TRUNCATE) and SIX_FILTERED_POLLER emptied, under a lock of SIX_FILTERED_POLLER.
 *     The poller rows are kept when a held file could not be moved (they are needed to decide again the next night).
 *  3. SIX_INSTRUMENTS / SIX_STRUCTURED / SIX_OPTION: every version older than the latest one deleted (the latest one
 *     is what a regeneration reads). A table whose latest version has no row (rollback not finished) is left as it is.
 * Never touched: VERSION, CTR_BATCH_JOB_EXECUTION, IMPORTED_FILE, CTR_BATCH_EXPORT.
 * An error stops the run (logged); every step can be run again, the next night continues.
 */
@Service
@ReglissBatchProfile
@Slf4j
public class SixCleanupService {

    /** ".tmp" files of the holding folder younger than this may still be written: kept. */
    static final long TMP_FILE_AGE_MINUTES = 60;
    private static final String GENERATION_SUFFIX = "_" + SixExportRunGuard.GENERATION;
    private static final String REGENERATION_SUFFIX = "_" + SixExportRunGuard.REGENERATION;
    private static final List<BatchJobType> SIX_JOB_TYPES = Collections.unmodifiableList(
            Arrays.asList(BatchJobType.BATCH_IMPORT_SIXRAW, BatchJobType.BATCH_EXPORT_SIXRAW));

    @Autowired
    private SixCleanupStore sixCleanupStore;

    @Autowired
    private SixFilteredStore sixFilteredStore;

    @Autowired
    private SixExportRunGuard sixExportRunGuard;

    @Autowired
    private SixXmlGenerationService sixXmlGenerationService;

    @Autowired
    private BatchJobExecutionRepository batchJobExecutionRepository;

    @Autowired
    private BatchExportRepository batchExportRepository;

    @Autowired
    private VersionRepository versionRepository;

    /** What the run did (log and tests). */
    public static final class Result {
        private String skippedBecause;
        private final List<String> delivered = new ArrayList<>();
        private final List<String> discarded = new ArrayList<>();
        private final List<String> notMoved = new ArrayList<>();
        private boolean exportTablesCleared;
        private boolean pollerCleared;
        private final Map<String, Integer> rawRowsDeleted = new LinkedHashMap<>();

        public String getSkippedBecause() { return skippedBecause; }
        public List<String> getDelivered() { return delivered; }
        public List<String> getDiscarded() { return discarded; }
        public List<String> getNotMoved() { return notMoved; }
        public boolean isExportTablesCleared() { return exportTablesCleared; }
        public boolean isPollerCleared() { return pollerCleared; }
        public Map<String, Integer> getRawRowsDeleted() { return rawRowsDeleted; }

        @Override
        public String toString() {
            return skippedBecause != null ? "skipped: " + skippedBecause
                    : "held files delivered " + delivered + ", removed (DJ already has newer) " + discarded + ", not moved " + notMoved
                    + ", filtered tables cleared " + exportTablesCleared + ", poller cleared " + pollerCleared
                    + ", raw rows deleted " + rawRowsDeleted;
        }
    }

    /** One cleanup run. Never throws: an error is logged and the next night runs again. */
    public Result runCleanup() {
        Result result = new Result();
        Optional<String> busy = whyBusy(true);
        if (busy.isPresent()) {
            result.skippedBecause = busy.get();
            log.info("SIX cleanup skipped today: {}", busy.get());
            return result;
        }
        log.info("SIX cleanup started");
        try {
            recoverHeldFiles(result);
            if (!stillIdle()) {
                return stopped(result);
            }
            SixCleanupStore.ClearResult cleared = sixCleanupStore.clearExportTables(this::stillIdle, result.notMoved.isEmpty());
            if (cleared != SixCleanupStore.ClearResult.CLEARED) {
                return stopped(result);
            }
            result.exportTablesCleared = true;
            result.pollerCleared = result.notMoved.isEmpty();
            if (!result.pollerCleared) {
                log.warn("SIX cleanup: SIX_FILTERED_POLLER kept, {} held file(s) could not be moved: {}", result.notMoved.size(), result.notMoved);
            }
            deleteOlderRawVersions(result);
            log.info("SIX cleanup finished: {}", result);
        } catch (RuntimeException e) {
            log.error("SIX cleanup stopped (the next run continues): {}", SixExportException.rootCause(e), e);
            result.skippedBecause = "error: " + SixExportException.rootCause(e);
        }
        return result;
    }

    private Result stopped(Result result) {
        result.skippedBecause = "a SIX job started during the cleanup";
        log.info("SIX cleanup stopped, {}: {}", result.skippedBecause, result);
        return result;
    }

    // ------------------------------------------------------------------------------------------ idle check

    private boolean stillIdle() {
        return !whyBusy(false).isPresent();
    }

    /** Empty when nothing SIX is in progress; else the reason (for the log). */
    Optional<String> whyBusy(boolean logStoppedJobs) {
        for (BatchJobExecution job : batchJobExecutionRepository.findByJobTypeInAndEndDateIsNull(SIX_JOB_TYPES)) {
            if (sixExportRunGuard.isJobWorking(job.getId())) {
                return Optional.of("SIX job " + job.getId() + " is running");
            }
            if (logStoppedJobs) {
                log.warn("SIX cleanup: job {} is not finished but does not work any more (server {} stopped or job stuck): not waited for",
                        job.getId(), job.getNodeId());
            }
        }
        if (batchExportRepository.countSixConfidenceEntries() > 0) {
            return Optional.of("a SIX delivery waits for its confidence step (SIX_CONFIDENCE_VALUES)");
        }
        if (batchExportRepository.countSixExportsNotStarted() > 0) {
            return Optional.of("a SIX export is requested and not yet started");
        }
        return listInProgress();
    }

    private Optional<String> listInProgress() {
        LocalDateTime koBefore = LocalDateTime.now().minusMinutes(SixExportRunGuard.KO_AFTER_MINUTES);
        Map<Long, Boolean> working = new HashMap<>();
        for (SixFilteredStore.PollerRow row : sixFilteredStore.rowsInStatus(SixFilteredStore.PENDING, SixFilteredStore.READY,
                SixFilteredStore.BUILDING, SixFilteredStore.BUILT)) {
            boolean inProgress;
            if (SixFilteredStore.BUILT.equals(row.getStatus())) {
                inProgress = true;
            } else if (SixFilteredStore.BUILDING.equals(row.getStatus())) {
                inProgress = row.getUpdatedTime() != null && row.getUpdatedTime().isAfter(koBefore);
            } else {
                inProgress = row.getBatchJobExecutionId() != null
                        && working.computeIfAbsent(row.getBatchJobExecutionId(), sixExportRunGuard::isJobWorking);
            }
            if (inProgress) {
                return Optional.of("SIX export list in progress: " + row);
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------------------------------ holding folder

    /**
     * Every CONVERTER file left in the holding folder goes to DJ IN, except when DJ already received the same or a
     * newer delivery of that list afterwards (DONE row of a later delivery, or of the same delivery released after
     * the file was built): then it is old data and is removed. Only the newest held file of a list can be delivered.
     */
    private void recoverHeldFiles(Result result) {
        List<SixXmlGenerationService.HeldFile> held;
        try {
            held = sixXmlGenerationService.heldFiles();
        } catch (IOException e) {
            throw new IllegalStateException("holding folder not readable: " + e.getMessage(), e);
        }
        if (!held.isEmpty()) {
            Map<String, Stamp> lastDelivered = lastDeliveredByListFolder();
            Map<String, List<SixXmlGenerationService.HeldFile>> byList = new LinkedHashMap<>();
            for (SixXmlGenerationService.HeldFile file : held) {
                byList.computeIfAbsent(file.getListFolder(), k -> new ArrayList<>()).add(file);
            }
            byList.forEach((list, files) -> recoverList(list, files, lastDelivered.get(list), result));
        }
        try {
            int tmp = sixXmlGenerationService.removeTempFilesAndEmptyFolders(TMP_FILE_AGE_MINUTES);
            if (tmp > 0) {
                log.info("SIX cleanup: {} unfinished '.tmp' file(s) removed from the holding folder", tmp);
            }
        } catch (IOException e) {
            log.warn("SIX cleanup: holding folder not fully tidied: {}", e.getMessage());
        }
    }

    private void recoverList(String list, List<SixXmlGenerationService.HeldFile> files, Stamp delivered, Result result) {
        files.sort(Comparator.comparing(SixCleanupService::stampOf));
        SixXmlGenerationService.HeldFile newest = files.get(files.size() - 1);
        for (SixXmlGenerationService.HeldFile file : files) {
            boolean deliver = file == newest && (delivered == null || stampOf(file).compareTo(delivered) > 0);
            try {
                if (deliver) {
                    sixXmlGenerationService.deliverHeldFile(file);
                    result.delivered.add(file.toString());
                    log.warn("SIX cleanup: left-over CONVERTER file {} of list {} moved to DJ IN", file, list);
                } else {
                    sixXmlGenerationService.discardHeldFile(file);
                    result.discarded.add(file.toString());
                    log.warn("SIX cleanup: left-over CONVERTER file {} removed: DJ already received {} for list {}", file,
                            file == newest ? delivered : "a newer file", list);
                }
            } catch (IOException | RuntimeException e) {
                result.notMoved.add(file.toString());
                log.error("SIX cleanup: left-over CONVERTER file {} could not be {}: {}", file, deliver ? "moved to DJ IN" : "removed",
                        SixExportException.rootCause(e), e);
            }
        }
    }

    /** Per list folder: the newest delivery that reached DJ IN (DONE rows still in SIX_FILTERED_POLLER). */
    private Map<String, Stamp> lastDeliveredByListFolder() {
        Map<String, Stamp> last = new HashMap<>();
        for (SixFilteredStore.PollerRow row : sixFilteredStore.rowsInStatus(SixFilteredStore.DONE)) {
            if (row.getListRef() == null || row.getRawVersionId() == null) {
                continue;
            }
            Stamp stamp = new Stamp(parseKey(sixExportRunGuard.deliveryOf(row.getRawVersionId())), row.getUpdatedTime());
            last.merge(SixXmlGenerationService.holdingFolderName(row.getListRef()), stamp, (a, b) -> a.compareTo(b) >= 0 ? a : b);
        }
        return last;
    }

    private static Stamp stampOf(SixXmlGenerationService.HeldFile file) {
        return new Stamp(deliveryKeyOfGroup(file.getGroup()), file.getLastModified());
    }

    /** Delivery key (date+time of the SIX files) of a holding group folder "&lt;delivery&gt;_&lt;REASON&gt;"; null if unknown. */
    static Long deliveryKeyOfGroup(String group) {
        String key = group;
        if (key.endsWith(REGENERATION_SUFFIX)) {
            key = key.substring(0, key.length() - REGENERATION_SUFFIX.length());
        } else if (key.endsWith(GENERATION_SUFFIX)) {
            key = key.substring(0, key.length() - GENERATION_SUFFIX.length());
        }
        return parseKey(key);
    }

    private static Long parseKey(String key) {
        if (key == null || key.isEmpty() || key.length() > 18 || !key.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return Long.valueOf(key);
    }

    /** Order of the data sent to DJ for a list: delivery first (unknown = oldest), then time of the file / release. */
    static final class Stamp implements Comparable<Stamp> {
        private static final Comparator<Stamp> ORDER = Comparator
                .comparing((Stamp s) -> s.delivery, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(s -> s.time, Comparator.nullsFirst(Comparator.naturalOrder()));
        private final Long delivery;
        private final LocalDateTime time;

        Stamp(Long delivery, LocalDateTime time) {
            this.delivery = delivery;
            this.time = time;
        }

        @Override
        public int compareTo(Stamp other) {
            return ORDER.compare(this, other);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Stamp && compareTo((Stamp) o) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(delivery, time);
        }

        @Override
        public String toString() {
            return "delivery " + delivery + " at " + time;
        }
    }

    // ------------------------------------------------------------------------------------------ raw tables

    private void deleteOlderRawVersions(Result result) {
        deleteOlderRawVersions(SixCleanupStore.RawTable.INSTRUMENTS, versionRepository::getLatestVersionIdOfInstruments, result);
        deleteOlderRawVersions(SixCleanupStore.RawTable.STRUCTURED, versionRepository::getLatestVersionIdOfStructure, result);
        deleteOlderRawVersions(SixCleanupStore.RawTable.OPTIONS, versionRepository::getLatestVersionIdOfOptions, result);
    }

    private void deleteOlderRawVersions(SixCleanupStore.RawTable raw, Supplier<Long> latestVersion, Result result) {
        Long latest = latestVersion.get();
        if (latest == null) {
            log.info("SIX cleanup: no {} version, nothing to delete", raw.getTable());
            return;
        }
        if (!sixCleanupStore.hasRowsOfVersion(raw, latest)) {
            log.warn("SIX cleanup: latest version {} has no row in {} (failed import not fully rolled back?): older versions kept",
                    latest, raw.getTable());
            return;
        }
        int rows = sixCleanupStore.deleteOlderVersions(raw, latest, this::stillIdle);
        result.rawRowsDeleted.put(raw.getTable(), rows);
        log.info("SIX cleanup: {} row(s) of versions older than {} deleted from {} (and their SIX_TARGET rows)", rows, latest, raw.getTable());
    }
}
