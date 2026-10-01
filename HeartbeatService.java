package com.bnpp.regliss.batch.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.bnpp.regliss.entity.Heartbeat.CftPublishingStatus.ACTIVE;
import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.toList;

@Service
@Slf4j
public class HeartbeatService {

    @Autowired
    private HeartbeatRepository heartbeatRepo;

    @Autowired
    private NodeDetailsSupplier nodeDetailsSupplier;

    @Value("${heartbeat.inactive.delta.seconds}")
    private int inactiveDeltaInSeconds;

    @Value("${heartbeat.inactive.publisher.delta.seconds}")
    private int inactiveDeltaPublisherInSeconds;

    @Transactional
    public void insertOrUpdateHeartbeat() {
        String nodeId = nodeDetailsSupplier.getNodeId();
        Heartbeat heartbeat = heartbeatRepo.findByNodeId(nodeId).orElseGet(() -> new Heartbeat(nodeId));
        updateHeartbeat(heartbeat);

        heartbeatRepo.saveAndFlush(heartbeat);
    }

    public boolean isNodeActivePublisher(){
        Heartbeat heartbeat = heartbeatRepo.findByNodeId(nodeDetailsSupplier.getNodeId()).orElseThrow(this::shouldHaveHeartbeatException);
        return Heartbeat.CftPublishingStatus.ACTIVE==heartbeat.getCftPublishingStatus();
    }

    public void markNodeAsActivePublisher() {

        Heartbeat heartbeat = heartbeatRepo.findByNodeId(nodeDetailsSupplier.getNodeId()).orElseThrow(this::shouldHaveHeartbeatException);
        heartbeat.setCftPublishingStatus(ACTIVE);

    }

    @Transactional( propagation = Propagation.REQUIRES_NEW)
    public Optional<String> markNodeAsActivePublisherIfOtherNodeIsDown() {
        if (nodeDetailsSupplier.isReglissPublisher()) {
            return getActivePublisherWithoutHeartbeat().map(this::changeCurrentNodeAsActivePublisher);
        }else{
            return Optional.empty();
        }
    }
    @Transactional( propagation = Propagation.REQUIRES_NEW)
    public Optional<String> markNodeAsActivePublisherIfIsPrimary() {

        if(nodeDetailsSupplier.isPrimaryReglissPublisher() ){
            Optional<Heartbeat> otherActivePublisher = getOtherActivePublisher(nodeDetailsSupplier.getNodeId());
            if(otherActivePublisher.isPresent()){
                return otherActivePublisher.map(this::changeCurrentNodeAsActivePublisher);
            }else{
                markNodeAsActivePublisher();
                return  Optional.empty();
            }

        }else return  Optional.empty();

    }

    private String changeCurrentNodeAsActivePublisher(Heartbeat h) {
        try {
            log.debug("attempt to set node {} as active ", nodeDetailsSupplier.getNodeId());
            Heartbeat heartbeat = heartbeatRepo.findByNodeId(nodeDetailsSupplier.getNodeId()).orElseThrow(this::shouldHaveHeartbeatException);
            h.setCftPublishingStatus(null);
            heartbeatRepo.flush();
            heartbeat.setCftPublishingStatus(ACTIVE);
            log.info("node {} set as active publisher since node {} is down", nodeDetailsSupplier.getNodeId(), h.getNodeId());
            return h.getNodeId();
        } catch (RuntimeException e) {
            log.error("could not set active node in transaction", e);
            log.error("Could not set node {} as active publisher. {}", nodeDetailsSupplier.getNodeId(), e.getMessage());
            return null;
        }
    }

    public Optional<Heartbeat> getNodeIfInactive(String nodeId) {
        return heartbeatRepo.findByNodeId(nodeId)
                .filter(this::hasNoHeartbeat);
    }
    public boolean isNodeAlive(String nodeId) {
        return !getNodeIfInactive(nodeId).isPresent();
    }

    public List<String> getAllActiveNodeIds() {
        return heartbeatRepo.findAll().stream()
                .filter(not(this::hasNoHeartbeat))
                .map(Heartbeat::getNodeId)
                .collect(toList());
    }

    public List<String> getAllActiveBatchNodeIds() {
        return heartbeatRepo.findAll().stream()
                .filter(not(this::hasNoHeartbeat))
                .filter(this::hasBatchActiveProfile)
                .map(Heartbeat::getNodeId)
                .collect(toList());
    }

    public Optional<Heartbeat> getActivePublisherWithoutHeartbeat() {
        return heartbeatRepo.findAll().stream()
                .filter(h -> ACTIVE.equals(h.getCftPublishingStatus()))
                .filter(this::hasNoHeartbeatPublisher)
                .findFirst();
    }

    public Optional<Heartbeat> getOtherActivePublisher(String currentPrimaryId){
        return heartbeatRepo.findAll().stream()
                .filter(h -> currentPrimaryId ==null || !currentPrimaryId.equals(h.getNodeId()))
                .filter(h -> ACTIVE.equals(h.getCftPublishingStatus()))
                .findFirst();
    }

    public List<String> getAllInactiveNodeIds() {
        return heartbeatRepo.findAll().stream()
                .filter(this::hasNoHeartbeat)
                .map(Heartbeat::getNodeId)
                .collect(toList());
    }

    private boolean hasNoHeartbeat(Heartbeat beat) {
        return Duration.between(beat.getLastBeat(), LocalDateTime.now()).getSeconds() > inactiveDeltaInSeconds;
    }
    private boolean hasNoHeartbeatPublisher(Heartbeat beat) {
        return Duration.between(beat.getLastBeat(), LocalDateTime.now()).getSeconds() > inactiveDeltaPublisherInSeconds;
    }

    private void updateHeartbeat(Heartbeat heartbeat) {
        heartbeat.setHostname(nodeDetailsSupplier.getHostName());
        heartbeat.setPort(nodeDetailsSupplier.getHttpPort().orElse(null));
        heartbeat.setActiveProfiles(String.join(",", nodeDetailsSupplier.getActiveProfiles()));
        heartbeat.setPid(Long.parseLong(new ApplicationPid().toString()));
        heartbeat.setCftType(nodeDetailsSupplier.getCftType());
        heartbeat.setLastBeat(LocalDateTime.now());
    }



    private IllegalStateException shouldHaveHeartbeatException() {
        return new IllegalStateException("Node with id " + nodeDetailsSupplier.getNodeId() + " should have a heartbeat.");
    }

    private boolean hasBatchActiveProfile(Heartbeat heartbeat) {
        return heartbeat.getActiveProfiles().contains(ReglissProfile.REGLISS_BATCH);
    }
}
