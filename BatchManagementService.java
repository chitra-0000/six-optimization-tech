package com.bnpp.regliss.batch.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
public class BatchManagementService {

    @Autowired
    private NodeDetailsSupplier nodeDetailsSupplier;

    @Autowired
    private ListImportNodeLockService listImportNodeLockService;

    @Autowired
    private BatchJobExecutionRepository batchJobExecutionRepository;

    @Autowired
    private HeartbeatService heartbeatService;

    @Autowired
    private VersionRepository versionRepository;

    @Autowired
    private BatchExportService batchExportService;

    @Autowired
    private ReglissRequestContext requestContext;
    @Autowired
    private ReferenceService referenceService;

    public boolean attemptLockList(ReglissList list) {
        try {
            listImportNodeLockService.createListLockInTx(list, nodeDetailsSupplier.getNodeId());
            return true;
        } catch (DataAccessException e) {
            log.debug("could not obtain list lock ", e);
            return false;
        }
    }

    public boolean attemptUnlockListWithNode(ReglissList list, String nodeId) {
        try {
            return listImportNodeLockService.removeListLockInTx(list, nodeId);
        } catch (DataAccessException e) {
            log.debug("could not remove list lock ", e);
            return false;
        }
    }
    public boolean attemptUnlockListWithCurrentNode(long listId) {
        try {
            return listImportNodeLockService.removeListLockInTx(listId, nodeDetailsSupplier.getNodeId());
        } catch (DataAccessException e) {
            log.debug("could not remove list lock ", e);
            return false;
        }
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long createNewSemiAutoBatchExecutionInTx(ImportedFile importedFile, Version version) {
        BatchImportSemiAutoLoggedParameters batchImportSemiAutoParameters = new BatchImportSemiAutoLoggedParameters(importedFile);
        ReglissList list = version == null ? null : version.getList();
        return createNewBatchJobExecutionInTx(BatchJobType.BATCH_IMPORT_SEMIAUTO, batchImportSemiAutoParameters, version, list);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long createNewAutoBatchExecutionInTx(AutomaticImportFeedBase feed, ReglissList reglissList) {
        BatchImportAutoLoggedParameters feedParams = new BatchImportAutoLoggedParameters(reglissList.getId(), feed);
        return createNewBatchJobExecutionInTx(BatchJobType.BATCH_IMPORT_AUTO, feedParams, null, reglissList);
    }


    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long createNewExportBatchExecutionInTx(BatchExport batchExport, Version version) {
        batchExportService.deleteProcessedBatchExport(batchExport.getId());
        return createNewBatchJobExecutionInTx(BatchJobType.BATCH_EXPORT, new BatchExportLoggedParameters(batchExport.getExportParams()),
                version, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateBatchExecutionVersionInTx(Long batchExecutionId, Long versionId) {
        BatchJobExecution batchJobExecution = batchJobExecutionRepository.getExactlyOne(batchExecutionId);
        batchJobExecution.setVersion(versionRepository.getExactlyOne(versionId));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markBatchExecutionFinishedBySys(long batchExecutionId) {
        BatchJobExecution batchJobExecution = markFinishTimeOnJob(batchExecutionId);
        batchJobExecution.setUnblockingUserId(referenceService.getSysUser().getId());
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markBatchExecutionFinishedByRequester(long batchExecutionId) {
        BatchJobExecution batchJobExecution = markFinishTimeOnJob(batchExecutionId);
        batchJobExecution.setUnblockingUserId(requestContext.getUserId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateBatchExecutionCompletedProgress(long batchExecutionId) {
        BatchJobExecution batchJobExecution = batchJobExecutionRepository.getExactlyOne(batchExecutionId);
        batchJobExecution.setPercent(100.0f);
        batchJobExecution.setLastUpdateDate(LocalDateTime.now());
    }

    private BatchJobExecution markFinishTimeOnJob(long batchExecutionId) {
        log.info("Marking batch execution finished");
        BatchJobExecution batchJobExecution = batchJobExecutionRepository.getExactlyOne(batchExecutionId);
        LocalDateTime now = LocalDateTime.now();
        batchJobExecution.setEndDate(now);
        batchJobExecution.setLastUpdateDate(now);
        return batchJobExecution;
    }

    public boolean currentNodeIsAllowedToStartImportAuto() {
        return !hasImportAutoInProgress(nodeDetailsSupplier.getNodeId()) || otherAliveBatchNodesHaveImportAutoInProgress();
    }

    private Long createNewBatchJobExecutionInTx(BatchJobType batchJobType, BatchJobParameterLoggable parameters, Version version, ReglissList reglissList) {
        BatchJobExecution batchJobExecution = new BatchJobExecution(nodeDetailsSupplier.getNodeId(), nodeDetailsSupplier.getHostName(), batchJobType, parameters);
        if (version != null) {
            batchJobExecution.setVersion(version);
            batchJobExecution.setReglissListId(version.getList().getId());
        }

        if (reglissList != null) {
            batchJobExecution.setReglissListId(reglissList.getId());
        }

        return batchJobExecutionRepository.save(batchJobExecution).getId();
    }

    private boolean hasImportAutoInProgress(String nodeId) {
        return !(batchJobExecutionRepository.findByNodeIdAndJobTypeInAndEndDateIsNull(nodeId, Arrays.asList(BatchJobType.BATCH_IMPORT_AUTO,
                BatchJobType.BATCH_IMPORT_SIXRAW)).isEmpty());
    }

    private boolean otherAliveBatchNodesHaveImportAutoInProgress() {
        List<String> aliveBatchNodeIds = heartbeatService.getAllActiveBatchNodeIds();
        aliveBatchNodeIds.removeIf(e -> e.equals(nodeDetailsSupplier.getNodeId()));
        return aliveBatchNodeIds.stream().allMatch(this::hasImportAutoInProgress);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long createNewSixRawAutoBatchExecutionInTx(AutomaticImportFeedBase feed, ReglissList reglissList) {
        BatchImportAutoLoggedParameters feedParams = new BatchImportAutoLoggedParameters(reglissList.getId(), feed);
        return createNewBatchJobExecutionInTx(BatchJobType.BATCH_IMPORT_SIXRAW, feedParams, null, reglissList);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long createNewSixRawExportBatchExecutionInTx(BatchExport batchExport, Version version) {
        batchExportService.deleteProcessedBatchExport(batchExport.getId());
        return createNewBatchJobExecutionInTx(BatchJobType.BATCH_EXPORT_SIXRAW, new BatchExportLoggedParameters(batchExport.getExportParams()), version, null);
    }
}
