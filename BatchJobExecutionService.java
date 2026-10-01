package com.bnpp.regliss.batch.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static java.util.stream.Collectors.toList;

@Slf4j
@Service
public class BatchJobExecutionService {
    @Value("${batchjob.inactive.delta.seconds}")
    private int inactiveDeltaInSeconds;

    @Autowired
    private HeartbeatService heartbeatService;
    @Autowired
    private BatchJobExecutionRepository batchRepo;

    public  List<BatchJobExecution> getStalledJobsForNode(String nodeId) {
        return batchRepo.findByNodeId(nodeId)
            .stream()
            .filter(BatchJobExecution::isNotFinished)
            .filter(this::noBatchJobActivityForDeltaSeconds)
            .collect(toList());
    }

    private boolean noBatchJobActivityForDeltaSeconds(BatchJobExecution job) {
        return Duration.between(job.getLastUpdateDate(), LocalDateTime.now()).getSeconds() > inactiveDeltaInSeconds;
    }

    public boolean isJobAlive(BatchJobExecution jobExecution) {
        return heartbeatService.isNodeAlive(jobExecution.getNodeId()) && isJobInProgress(jobExecution);
    }

    private boolean isJobInProgress(BatchJobExecution jobExecution) {
        if(jobExecution.isFinished()){
            return false;
        }
        long secondsSinceLastUpdate = Math.abs(Duration.between(jobExecution.getLastUpdateDate(), LocalDateTime.now()).getSeconds());
        return secondsSinceLastUpdate < inactiveDeltaInSeconds;
    }
}
