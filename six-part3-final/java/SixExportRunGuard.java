package com.bnpp.regliss.importer.six.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// TODO: re-add project imports for: ReglissBatchProfile, SixFilteredPoller, SixFilteredPollerRepository,
// BatchJobExecution, BatchJobExecutionRepository, ImportedFile, ImportedFileRepository, SixDeliveryService

/**
 * Failure signals of one SIX delivery, shared by the two batch servers: rows of SIX_FILTERED_POLLER with
 * STATUS = FAILED / FAILED_ALL (FILE_TYPE = the file type of the export that failed):
 *  - FAILED     (SIX_LIST_REFERENCE = the list): one list failed while filtering / writing / building its XML.
 *               The other server skips that list (or drops it if already written): no half file.
 *  - FAILED_ALL (SIX_LIST_REFERENCE = the list being processed, if any): the error concerns every list
 *               (preparation, CMIC / E014071 exclusion, database not reachable). Both servers stop.
 *
 * Same delivery = same date+time in the SIX file names (SixDeliveryService.deliveryKey, as in part 2), read from the
 * imported file of the raw version.
 *
 * Regeneration: a regeneration is a new attempt, so it only sees the failures that happened AFTER it started, never
 * the old failure it repairs. The rows are removed by the nightly cleanup.
 */
@Component
@ReglissBatchProfile
@Slf4j
public class SixExportRunGuard {

    public static final String FAILED = "FAILED";
    public static final String FAILED_ALL = "FAILED_ALL";
    private static final String REGENERATION = "REGENERATION";

    @Autowired
    private SixFilteredPollerRepository sixFilteredPollerRepository;

    @Autowired
    private BatchJobExecutionRepository batchJobExecutionRepository;

    @Autowired
    private ImportedFileRepository importedFileRepository;

    /** Delivery key per raw version (a version never changes its file). */
    private final Map<Long, String> keyByVersion = new ConcurrentHashMap<>();

    /** Delivery of a raw version: date+time of its SIX file name ("version:<id>" if the name has no date). */
    public String deliveryOf(long rawVersionId) {
        return keyByVersion.computeIfAbsent(rawVersionId, v -> {
            ImportedFile file = importedFileRepository.findByVersionId(v);
            Long key = file == null ? null : SixDeliveryService.deliveryKey(file.getOriginalFileName());
            return key == null ? "version:" + v : String.valueOf(key);
        });
    }

    /** One list failed: the other server must not use / build this list. */
    public void listFailed(String fileType, long rawListId, long rawVersionId, String listRef, Long batchJobExecutionId) {
        save(fileType, FAILED, rawListId, rawVersionId, listRef, batchJobExecutionId);
    }

    /** The error concerns every list: both servers stop the export of this delivery. */
    public void deliveryFailed(String fileType, long rawListId, long rawVersionId, String listRef, Long batchJobExecutionId) {
        save(fileType, FAILED_ALL, rawListId, rawVersionId, listRef, batchJobExecutionId);
    }

    private void save(String fileType, String status, long rawListId, long rawVersionId, String listRef, Long batchJobExecutionId) {
        sixFilteredPollerRepository.save(new SixFilteredPoller(fileType, status, LocalDateTime.now(), rawListId, rawVersionId,
                listRef, batchJobExecutionId));
        log.error("SIX delivery {}: {} recorded ({} file, list {}, raw version {}, job {})", deliveryOf(rawVersionId), status,
                fileType, listRef, rawVersionId, batchJobExecutionId);
    }

    /** A FAILED_ALL of the same delivery that this job must obey. */
    public Optional<SixFilteredPoller> deliveryStop(long rawVersionId, Long batchJobExecutionId) {
        return find(FAILED_ALL, rawVersionId, batchJobExecutionId, null);
    }

    /** A FAILED of this list, else a FAILED_ALL, of the same delivery that this job must obey. */
    public Optional<SixFilteredPoller> listStop(long rawVersionId, Long batchJobExecutionId, String listRef) {
        Optional<SixFilteredPoller> list = find(FAILED, rawVersionId, batchJobExecutionId, listRef);
        return list.isPresent() ? list : deliveryStop(rawVersionId, batchJobExecutionId);
    }

    private Optional<SixFilteredPoller> find(String status, long rawVersionId, Long batchJobExecutionId, String listRef) {
        List<SixFilteredPoller> failures = sixFilteredPollerRepository.findByStatus(status);
        if (failures.isEmpty()) {
            return Optional.empty();
        }
        String delivery = deliveryOf(rawVersionId);
        LocalDateTime regenerationStart = regenerationStart(batchJobExecutionId);
        return failures.stream()
                .filter(f -> f.getRawVersionId() != null && delivery.equals(deliveryOf(f.getRawVersionId())))
                .filter(f -> listRef == null || listRef.equals(f.getSixListReference()))
                .filter(f -> regenerationStart == null || (f.getInsertionTime() != null && !f.getInsertionTime().isBefore(regenerationStart)))
                .findFirst();
    }

    /** Text of a stop for the server log. */
    public String describe(SixFilteredPoller failure) {
        return (FAILED_ALL.equals(failure.getStatus()) ? "the export of delivery " : "list " + failure.getSixListReference() + " of delivery ")
                + deliveryOf(failure.getRawVersionId()) + " failed at " + failure.getInsertionTime() + " (raw version "
                + failure.getRawVersionId() + ", job " + failure.getBatchJobExecutionId() + ")";
    }

    /** Start of the job when it is a regeneration, null for the normal export after an import. */
    private LocalDateTime regenerationStart(Long batchJobExecutionId) {
        if (batchJobExecutionId == null) {
            return null;
        }
        return batchJobExecutionRepository.findById(batchJobExecutionId)
                .filter(job -> job.getJobParams() != null && job.getJobParams().contains(REGENERATION))
                .map(BatchJobExecution::getStartDate)
                .orElse(null);
    }
}
