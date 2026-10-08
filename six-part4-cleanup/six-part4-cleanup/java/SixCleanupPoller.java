package com.bnpp.regliss.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

// TODO: re-add project imports for: ReglissBatchProfile, HeartbeatService, NodeDetailsSupplier, SixCleanupService

/**
 * SIX part 4: starts the nightly SIX cleanup (SixCleanupService) once a day, property task.six.cleanup.cron.
 * Both batch servers fire; only ONE runs it: the server with the smallest node id among the batch servers whose
 * heartbeat is alive (HeartbeatService.getAllActiveBatchNodeIds). If that server is down, the other one runs it.
 * When something SIX is in progress the cleanup is skipped until the next day (no retry).
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

    @Scheduled(cron = "${task.six.cleanup.cron:0 0 2 * * *}")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void cleanSix() {
        try {
            String node = nodeDetailsSupplier.getNodeId();
            Optional<String> runner = cleanupNode(heartbeatService.getAllActiveBatchNodeIds());
            if (!runner.isPresent() || !runner.get().equals(node)) {
                log.info("SIX cleanup: runs on batch server {}, not on {}", runner.orElse("(none alive)"), node);
                return;
            }
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
