package com.bnpp.regliss.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

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

        Optional<SixDeliveryService.Delivery> found = deliveryService.findWaitingDelivery();
        if (!found.isPresent()) {
            return;
        }
        SixDeliveryService.Delivery delivery = found.get();
        log.debug("{}", delivery);

        if (delivery.isFinishing()) {
            finishDelivery(delivery);            // resume a finish interrupted on this or another server
            return;
        }
        if (delivery.isCancelled()) {
            deliveryService.rollbackDelivery(delivery.getKey(), null, "a file of the delivery was moved out of the IN folder");
            return;
        }
        if (delivery.hasPendingOrRunning()) {
            return;                              // other files still importing: keep waiting at 90%
        }
        if (!delivery.instrument().isPresent()) {
            deliveryService.rollbackDelivery(delivery.getKey(), null, "no instrument file in the delivery");
            return;
        }

        // ---- step 2: confidence merges, one per dependent file, exactly once ----
        long instrumentVersionId = delivery.instrument().get().getRow().getVersionId();
        for (SixDeliveryService.DeliveryFile file : delivery.members()) {
            if (file.confidenceDone()) {
                continue;
            }
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
            } catch (Exception e) {
                log.error("SIX delivery {}: confidence update of {} failed", delivery.getKey(), file.getKind(), e);
                deliveryService.rollbackDelivery(delivery.getKey(), null,
                        "confidence update of " + file.getKind() + " failed: " + e.getMessage());
                return;
            }
        }

        // ---- step 3: finish, by one server only ----
        Optional<SixDeliveryService.Delivery> reloaded = deliveryService.findWaitingDelivery();
        if (!reloaded.isPresent() || !reloaded.get().getKey().equals(delivery.getKey())) {
            return;
        }
        delivery = reloaded.get();
        boolean allMerged = delivery.members().stream().allMatch(SixDeliveryService.DeliveryFile::confidenceDone);
        if (!allMerged) {
            return;                              // another server is still merging: next tick
        }
        long instrumentRowId = delivery.instrument().get().getRow().getExportId();
        if (store.takeRow(instrumentRowId, deliveryService.currentNodeId())) {
            finishDelivery(deliveryService.findWaitingDelivery().orElse(delivery));
        }
    }

    private void finishDelivery(SixDeliveryService.Delivery delivery) {
        SixDeliveryService.DeliveryFile instrument = delivery.instrument().orElse(null);
        if (instrument == null || instrument.getRow() == null) {
            return;
        }
        String me = deliveryService.currentNodeId();
        String holder = instrument.getRow().getBatchNodeId();
        if (holder != null && !holder.equals(me)) {
            if (heartbeatService.isNodeAlive(holder)) {
                return;                          // the other server is finishing it
            }
            if (!store.takeOverRow(instrument.getRow().getExportId(), holder, me)) {
                return;
            }
            log.warn("SIX delivery {}: server {} stopped while finishing, taken over by {}", delivery.getKey(), holder, me);
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
}
