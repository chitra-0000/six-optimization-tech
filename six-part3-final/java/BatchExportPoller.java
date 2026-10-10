package com.bnpp.regliss.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
@ReglissBatchProfile
@Slf4j
public class BatchExportPoller {

    @Autowired
    private GenerateVersionJob generateVersionJob;

    @Autowired
    private GenerateConcatenatedListJob generateConcatenatedListJob;

    @Autowired
    private BatchExportService batchExportService;

    @Autowired
    private BatchManagementService batchManagementService;

    @Autowired
    private ReglissRequestContext requestContext;

    @Autowired
    private BatchExportRepository batchExportRepository;

    @Autowired
    private GeneratedFileService generatedFileService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SixFilterExtractExport sixFilterExtractExport;

    @Autowired
    @Qualifier("exportExecutor")
    private ThreadPoolTaskExecutor executor;

    @Scheduled(cron = "${task.batch.export.generation}")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @CloseResourcesAfter
    public void generateExportFiles() {
        BatchExport batchExport = batchExportRepository.getOrderedBatchExportRecords();
        while (thereAreFreeThreads() && null != batchExport && BatchExportType.SIX_CONFIDENCE_VALUES != batchExport.getExportParams().getExportType()) {
            if(batchExportService.tryLockBatchExport(batchExport.getId())) {
                log.debug("Found batch export record {}", batchExport.getId());
                Version version = batchExport.getExportParams().getVersionOpt().orElse(null);
                BatchExportType batchExportType = batchExport.getExportParams().getExportType();
                Long batchJobExecutionId = getBatchJobExecutionBasedOnExportType(batchExport, version, batchExportType);
                User laungerUser = userRepository.findByUsername(batchExport.getExportParams().getLauncherUsername());
                requestContext.setCurrentUser(laungerUser);
                //set request time the start of actual export process - will be set as version publication date
                requestContext.setRequestTime(LocalDateTime.now());
                try {
                    batchExport.setBatchExecutionId(batchJobExecutionId);
                    generateFiles(batchExport);
                } catch (Exception e) {
                    loggingError(batchExportType, e);
                    updateAddModSup(batchExport, e, batchExportType);
                }
                if (batchExportType != BatchExportType.SIX_FILTERED_FILE_GENERATION && batchExportType != BatchExportType.SIX_FILTERED_FILE_REGENERATION) {
                    finishBatchExecution(batchJobExecutionId);
                }

            }
            batchExport = batchExportRepository.getOrderedBatchExportRecords();
        }
    }

    private void updateAddModSup(BatchExport batchExport, Exception e, BatchExportType batchExportType) {
        if(batchExportType == BatchExportType.ADD_MOD_SUP_FILE) {
            generatedFileService.updateAddModSupOnErrorInTx(batchExport.getExportParams().getGeneratedFileIds().get(0), e);
        } else if (batchExportType == BatchExportType.ADD_MOD_SUP_AUDIT_TRAIL_FILE) {
            generatedFileService.updateAddModSupAuditTrailOnErrorInTx(batchExport.getExportParams().getGeneratedFileIds().get(0), e);
        } else if (batchExportType == BatchExportType.SIX_FILTERED_FILE_GENERATION || batchExportType == BatchExportType.SIX_FILTERED_FILE_REGENERATION){
            log.info("Error in generating six files");
        }
        else {
            generatedFileService.updateGeneratedFileStatusOnErrorInTx(batchExport.getExportParams().getGeneratedFileIds(), e);
        }
    }

    private void loggingError(BatchExportType batchExportType, Exception e) {
        if (batchExportType != BatchExportType.SIX_FILTERED_FILE_GENERATION && batchExportType != BatchExportType.SIX_FILTERED_FILE_REGENERATION) {
            log.error("Error during generating export files: {}", e);
        } else {
            log.error("Error during generating filtered files for six: {}", e);
        }
    }

    private boolean thereAreFreeThreads() {
        return(executor.getMaxPoolSize()-executor.getActiveCount()>0);
    }

    private void generateFiles(BatchExport batchExport) {
        switch(batchExport.getExportParams().getExportType()) {
            case VERSION_GENERATED_FILE :
                generateVersionFiles(batchExport);
                break;
            case CONCATENATED_LIST_GENERATED_FILE :
                generateConcatenatedListFiles(batchExport);
                break;
            case ADD_MOD_SUP_FILE:
                generateAddModSupFile(batchExport);
                break;
            case ADD_MOD_SUP_AUDIT_TRAIL_FILE:
                generateAddModSupAuditTrailFile(batchExport);
                break;
            case SIX_FILTERED_FILE_GENERATION:
                generateFilteredSixFiles(batchExport);
                break;
            case SIX_FILTERED_FILE_REGENERATION:
                generateFilteredSixFiles(batchExport);
                break;
        default:
            throw new IllegalArgumentException("Not a valid action: " + batchExport.getExportParams().getExportType());
        }

    }

    private void generateVersionFiles(BatchExport batchExport) {
        log.info("Generating version files: {}", batchExport);
        generateVersionJob.generateVersionFiles(batchExport);
        log.debug("Version Generation COMPLETED: {}", batchExport);
    }

    private void generateAddModSupFile(BatchExport batchExport) {
        log.info("Generating AddModSup file for: {}", batchExport);
        generateVersionJob.generateAddModSupFile(batchExport);
        log.debug("Version AddModSup file generation COMPLETED: {}", batchExport);
    }

    private void generateAddModSupAuditTrailFile(BatchExport batchExport) {
        log.info("Generating AddModSupAuditTrail file for: {}", batchExport);
        generateVersionJob.generateAddModSupAuditTrailFile(batchExport);
        log.debug("Version AddModSupAuditTrail file generation COMPLETED: {}", batchExport);
    }

    private void generateConcatenatedListFiles(BatchExport batchExport) {
        log.info("Generating Concatenated List files: {}", batchExport);
        generateConcatenatedListJob.generateConcatenatedListFiles(batchExport);
        log.debug("Concatenated List generation COMPLETED");
    }

    private void generateFilteredSixFiles(BatchExport batchExport) {
        sixFilterExtractExport.filteringSixRawFiles(batchExport);
    }

    private void finishBatchExecution(Long batchExecutionId) {
        if(batchExecutionId != null) {
            try{
                batchManagementService.markBatchExecutionFinishedBySys(batchExecutionId);
                batchManagementService.updateBatchExecutionCompletedProgress(batchExecutionId);
            }catch (Exception e) {
                log.error("could not update finished batchExecution {}", e.getMessage());
            }
        }
    }

    private Long getBatchJobExecutionBasedOnExportType(BatchExport batchExport, Version version, BatchExportType batchExportType) {
        if (batchExportType == BatchExportType.SIX_FILTERED_FILE_GENERATION || batchExportType == BatchExportType.SIX_FILTERED_FILE_REGENERATION) {
            return batchManagementService.createNewSixRawExportBatchExecutionInTx(batchExport, version);
        } else {
            return batchManagementService.createNewExportBatchExecutionInTx(batchExport, version);
        }
    }

}
