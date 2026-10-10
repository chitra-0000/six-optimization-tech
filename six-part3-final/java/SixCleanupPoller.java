package com.bnpp.regliss.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

// TODO: re-add project imports for: ReglissBatchProfile, HeartbeatService, NodeDetailsSupplier, SixCleanupService

/**
 * SIX part 4: starts the nightly SIX cleanup (SixCleanupService), property task.six.cleanup.cron.
 * Default "0 0/15 2 * * *": 02:00, 02:15, 02:30, 02:45.
 *
 * Both batch servers fire; only ONE runs it: the server with the smallest node id among the batch servers whose
 * heartbeat is alive (HeartbeatService.getAllActiveBatchNodeIds). If that server is down, the other one runs it.
 *  - No alive batch server found (or the heartbeat could not be read): tried again at the next tick (every 15 min,
 *    last one 02:45). After 02:45 the cleanup waits for the next day.
 *  - Once this server has run the cleanup (cleaned, or skipped because something SIX is in progress), the later ticks
 *    of the same day do nothing: the cleanup itself is never retried.
 */
@Component
@ReglissBatchProfile
@Slf4j
public class SixCleanupPoller {

    @Autowired
    private SixCleanupService sixCleanupService;

    @Autowired
    private HeartbeatService heartbeatService;

    @Autowired
    private NodeDetailsSupplier nodeDetailsSupplier;

    /** Day of the last cleanup run on this server (in memory: a restart may run it once more, it is safe to repeat). */
    private volatile LocalDate lastRunDay;

    @Scheduled(cron = "${task.six.cleanup.cron:0 0/15 2 * * *}")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void cleanSix() {
        cleanSix(LocalDate.now());
    }

    void cleanSix(LocalDate today) {
        if (today.equals(lastRunDay)) {
            log.debug("SIX cleanup: already run today on this server");
            return;
        }
        String node = nodeDetailsSupplier.getNodeId();
        Optional<String> runner;
        try {
            runner = cleanupNode(heartbeatService.getAllActiveBatchNodeIds());
        } catch (RuntimeException e) {
            log.warn("SIX cleanup: batch servers could not be read ({}), tried again at the next tick", e.getMessage());
            return;
        }
        if (!runner.isPresent()) {
            log.warn("SIX cleanup: no alive batch server found (heartbeat), tried again at the next tick (last one 02:45)");
            return;
        }
        if (!runner.get().equals(node)) {
            log.info("SIX cleanup: runs on batch server {}, not on {}", runner.get(), node);
            return;
        }
        lastRunDay = today;
        try {
            sixCleanupService.runCleanup();
        } catch (RuntimeException e) {
            log.error("SIX cleanup not run: {}", e.getMessage(), e);
        }
    }

    /** The batch server that runs the cleanup: the smallest node id among the alive ones (same answer on both servers). */
    static Optional<String> cleanupNode(List<String> aliveBatchNodeIds) {
        return aliveBatchNodeIds == null ? Optional.empty()
                : aliveBatchNodeIds.stream().filter(id -> id != null && !id.isEmpty()).min(String::compareTo);
    }
}
