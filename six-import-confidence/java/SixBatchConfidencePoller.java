package com.bnpp.regliss.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


// TODO: re-add project imports (Alt+Enter / Optimize Imports) for:
// ReglissBatchProfile, CloseResourcesAfter, BatchProgressService, HeartbeatService,
// ReglissRequestContext, ReferenceService,
// SixDeliveryService (+ its nested Delivery, DeliveryFile, FileState), SixConfidenceStore, SixFileKind

/**
 * Confidence step of a SIX delivery (replaces the old "size() >= 2" poller).
 *
 * Runs every tick on every server; each step is taken by exactly one server:
 *
 *  1. Wait until EVERY file of the delivery is imported (no file pending or running).
 *     A cancelled or incomplete delivery is rolled back (all or nothing).
 *  2. One confidence MERGE per dependent file (STRUCT, OPT, ... from SixFileKind). Each is taken with a
 *     row lock (SKIP LOCKED): with 2 servers, server A merges STRUCT while server B merges OPT in
 *     parallel; with 1 server they run one after the other. Each runs exactly once.
 *  3. When all merges are done, ONE server takes the instrument row and finishes the delivery:
 *     files deleted from IN, filtered export (part 3) created once per file, 100%, jobs closed, lists unlocked.
 *     If that server dies in the middle, the other server takes over at its next tick.
 */
@Component
@ReglissBatchProfile
@Slf4j
public class SixBatchConfidencePoller {

    @Autowired
    private SixDeliveryService deliveryService;

    @Autowired
    private SixConfidenceStore store;

    @Autowired
    private BatchProgressService batchProgressService;

    @Autowired
    private HeartbeatService heartbeatService;

    @Autowired
    private ReglissRequestContext requestContext;

    @Autowired
    private ReferenceService referenceService;

    /** Progress added to a file when its confidence merge is done (90% -> 95%); 100% is set at the end. */
    private static final int MERGE_DONE_PERCENT = 5;

    @Scheduled(cron = "${task.batch.export.generation}")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @CloseResourcesAfter
    public void generateExportFiles() {
        requestContext.setCurrentUser(referenceService.getSysUser());
        deliveryService.findWaitingDelivery().ifPresent(this::process);
    }

    private void process(SixDeliveryService.Delivery delivery) {
        log.debug("{}", delivery);
        if (delivery.isFinishing()) {
            finishDelivery(delivery);            // resume a finish interrupted on this or another server
            return;
        }
        if (isReadyForConfidence(delivery) && mergeAll(delivery)) {
            finishWhenAllMerged(delivery.getKey());
        }
    }

    /** Step 1: every file imported? Rolls back a cancelled or incomplete delivery. */
    private boolean isReadyForConfidence(SixDeliveryService.Delivery delivery) {
        if (delivery.isCancelled()) {
            deliveryService.rollbackDelivery(delivery.getKey(), null, "a file of the delivery was moved out of the IN folder");
            return false;
        }
        if (delivery.hasPendingOrRunning()) {
            return false;                        // other files still importing: keep waiting at 90%
        }
        if (!delivery.instrument().isPresent()) {
            deliveryService.rollbackDelivery(delivery.getKey(), null, "no instrument file in the delivery");
            return false;
        }
        return true;
    }

    /** Step 2: one confidence merge per dependent file, exactly once. False if the delivery was rolled back. */
    private boolean mergeAll(SixDeliveryService.Delivery delivery) {
        Long instrumentVersionId = delivery.instrument()
                .map(SixDeliveryService.DeliveryFile::getRow)
                .map(SixDeliveryService.WaitingRow::getVersionId)
                .orElse(null);
        if (instrumentVersionId == null) {
            deliveryService.rollbackDelivery(delivery.getKey(), null, "the instrument file has no version");
            return false;
        }
        for (SixDeliveryService.DeliveryFile file : delivery.members()) {
            if (!file.confidenceDone() && !mergeOne(delivery, file, instrumentVersionId)) {
                return false;
            }
        }
        return true;
    }

    private boolean mergeOne(SixDeliveryService.Delivery delivery, SixDeliveryService.DeliveryFile file, long instrumentVersionId) {
        try {
            deliveryService.keepWaitingFilesAlive(-1L);
            long start = System.currentTimeMillis();
            int updated = store.mergeConfidenceOnce(file.getRow().getExportId(), file.getKind(),
                    file.getRow().getVersionId(), instrumentVersionId, deliveryService.currentNodeId());
            if (updated >= 0) {
                log.info("SIX delivery {}: confidence of {} updated from the instrument file: {} rows in {}s",
                        delivery.getKey(), file.getKind(), updated, (System.currentTimeMillis() - start) / 1000);
                batchProgressService.incrementExportBatchForSix(file.getJobId(), MERGE_DONE_PERCENT);
            } else {
                log.debug("SIX delivery {}: {} merged by another server", delivery.getKey(), file.getKind());
            }
            return true;
        } catch (Exception e) {
            log.error("SIX delivery {}: confidence update of {} failed", delivery.getKey(), file.getKind(), e);
            deliveryService.rollbackDelivery(delivery.getKey(), null,
                    "confidence update of " + file.getKind() + " failed: " + e.getMessage());
            return false;
        }
    }

    /** Step 3: when every merge is done (here or on the other server), ONE server finishes the delivery. */
    private void finishWhenAllMerged(Long key) {
        SixDeliveryService.Delivery delivery = deliveryService.findWaitingDelivery()
                .filter(d -> d.getKey().equals(key))
                .orElse(null);
        if (delivery == null
                || !delivery.members().stream().allMatch(SixDeliveryService.DeliveryFile::confidenceDone)) {
            return;                              // another server is still merging: next tick
        }
        Long instrumentRowId = delivery.instrument()
                .map(SixDeliveryService.DeliveryFile::getRow)
                .map(SixDeliveryService.WaitingRow::getExportId)
                .orElse(null);
        if (instrumentRowId != null && store.takeRow(instrumentRowId, deliveryService.currentNodeId())) {
            finishDelivery(deliveryService.findWaitingDelivery().orElse(delivery));
        }
    }

    private void finishDelivery(SixDeliveryService.Delivery delivery) {
        SixDeliveryService.DeliveryFile instrument = delivery.instrument().orElse(null);
        if (instrument == null || instrument.getRow() == null || !holdsFinishMark(delivery, instrument.getRow())) {
            return;
        }
        try {
            // dependent files first, the instrument (which holds the "finishing" mark) last
            for (SixDeliveryService.DeliveryFile file : delivery.members()) {
                if (!file.getKind().isSource()) {
                    deliveryService.finishFile(delivery, file);
                }
            }
            deliveryService.finishFile(delivery, instrument);
            log.info("SIX delivery {} finished: all files at 100%, filtered exports created", delivery.getKey());
        } catch (Exception e) {
            // Nothing is lost: the instrument row still marks the delivery as finishing, the next tick resumes it.
            log.error("SIX delivery {}: finishing failed, will be resumed at the next tick", delivery.getKey(), e);
        }
    }

    /** True if this server holds the instrument row, taking it over from a server that is no longer alive. */
    private boolean holdsFinishMark(SixDeliveryService.Delivery delivery, SixDeliveryService.WaitingRow instrumentRow) {
        String me = deliveryService.currentNodeId();
        String holder = instrumentRow.getBatchNodeId();
        if (holder == null || holder.equals(me)) {
            return true;
        }
        if (heartbeatService.isNodeAlive(holder) || !store.takeOverRow(instrumentRow.getExportId(), holder, me)) {
            return false;                        // the other server is finishing it (or took it over first)
        }
        log.warn("SIX delivery {}: server {} stopped while finishing, taken over by {}", delivery.getKey(), holder, me);
        return true;
    }
}
