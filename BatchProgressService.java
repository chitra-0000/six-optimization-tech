package com.bnpp.regliss.batch.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

// TODO: the project imports were collapsed ("import ...") in the screenshots.
// Re-add them in the IDE (Alt+Enter / Optimize Imports) for:
// ReglissBatchProfile, BatchJobExecutionRepository, ImportAutoPhase, ImportConfiguration,
// ExportJobParameters, ExportQueryType, ExportPhase, ExportPageType, ExportProgressCalculator

@Slf4j
@Service
@ReglissBatchProfile
public class BatchProgressService {

    @Autowired
    private BatchJobExecutionRepository batchJobExecutionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void incrementImportSemiAutoBatchExecution(long batchExecutionId, int numberOfLatestProcessedElements, int totalNumberOfElements) {
        int percent = 100 * numberOfLatestProcessedElements / totalNumberOfElements;
        incrementProgress(batchExecutionId, percent);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void incrementImportAutoFeedValidatorBatchExecution(long batchExecutionId, ImportAutoPhase phase, ImportConfiguration config) {
        int weight = phase.calculateWeight(config);
        incrementProgress(batchExecutionId, weight);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void incrementImportAutoBatchExecution(long batchExecutionId, ImportAutoPhase phase, ImportConfiguration config,
                                                  long numberOfLatestProcessedElements, long totalNumberOfElements) {

        int weight = phase.calculateWeight(config);
        float reportBetweenLastNumberAndTotalNumberOfElements = (float) numberOfLatestProcessedElements / (float) totalNumberOfElements;
        float percent = weight * reportBetweenLastNumberAndTotalNumberOfElements;
        incrementProgress(batchExecutionId, percent);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void incrementBatchExportProgressAfterExtractData(ExportJobParameters exportJobParameters, ExportQueryType queryType) {
        if (!exportJobParameters.queryTypesToFiles().containsKey(queryType)) {
            throw new IllegalArgumentException("Not a valid query" + queryType + " for the export job: " + exportJobParameters);
        }
        float percentToIncrementAfterFetchRecords = ExportProgressCalculator.getPercentToIncrementAfterFetchRecords(exportJobParameters);
        incrementProgress(exportJobParameters.getBatchJobExecId(), percentToIncrementAfterFetchRecords);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void incrementBatchExportProgressForWritingAllPagesAtOnceOrNoPages(ExportPhase exportPhase, int numberOfWrittenFiles,
                                                                              ExportJobParameters exportJobParameters) {

        float percentToIncrementAfterWriteRecords = ExportProgressCalculator.getPercentToIncrementForWritingAllPagesAtOnceOrNoPages(exportPhase,
                numberOfWrittenFiles, exportJobParameters);
        log.debug("WRITING ALL PAGES OR NO PAGES, times: {}", numberOfWrittenFiles);
        incrementProgress(exportJobParameters.getBatchJobExecId(), percentToIncrementAfterWriteRecords);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void incrementBatchExportProgressForWritingPage(ExportPageType pageType, int numberOfWrittenFiles, int totalNumberOfExportedElements,
                                                           int pageSize, ExportJobParameters exportJobParameters) {

        float percentToIncrementForRecordsPage = ExportProgressCalculator.getPercentToIncrementForWritingPage(pageType, numberOfWrittenFiles,
                totalNumberOfExportedElements, pageSize, exportJobParameters);
        log.debug("WRITING A PAGE, times: {}", numberOfWrittenFiles);
        incrementProgress(exportJobParameters.getBatchJobExecId(), percentToIncrementForRecordsPage);
    }


    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void incrementExportProgressForXsdValidation(ExportJobParameters exportJobParameters) {
        float incrementValue = ExportProgressCalculator.getPercentToIncrementForXsdValidation(exportJobParameters);
        incrementProgress(exportJobParameters.getBatchJobExecId(), incrementValue);
    }

    private void incrementProgress(long batchExecutionId, float incrementValue) {
        float formattedPercentValue = BigDecimal.valueOf(incrementValue)
                .setScale(4, RoundingMode.DOWN).floatValue();
        batchJobExecutionRepository.incrementPercent(batchExecutionId, formattedPercentValue, LocalDateTime.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void incrementExportBatchForSix(long batchExecutionId, int percent) {
        incrementProgress(batchExecutionId, percent);
    }
}
