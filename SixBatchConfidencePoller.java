package com.bnpp.regliss.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;    // IDE showed "Cannot resolve symbol 'Autowired'"
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// TODO: re-add project imports (Alt+Enter / Optimize Imports) for:
// ReglissBatchProfile, CloseResourcesAfter, StructureFileRepository, OptionsFileRepository, BatchExportRepository,
// BatchExportService, BatchManagementService, UnblockImportService, BatchProgressService,
// BatchExport, BatchExportType, GenerationReason, Version, ImportFileType, ReglissException

@Component
@ReglissBatchProfile
@Slf4j
public class SixBatchConfidencePoller {

    @Autowired
    private StructureFileRepository structureFileRepository;

    @Autowired
    private OptionsFileRepository optionsFileRepository;

    @Autowired
    private BatchExportRepository batchExportRepository;

    @Autowired
    private BatchExportService batchExportService;

    @Autowired
    private BatchManagementService batchManagementService;

    @Autowired
    private UnblockImportService unblockImportService;

    @Autowired
    private BatchProgressService batchProgressService;

    @Scheduled(cron = "${task.batch.export.generation}")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @CloseResourcesAfter
    public void generateExportFiles() {
        List<BatchExport> batchExport = batchExportRepository.getByBatchType();

        if (batchExport.size() == 2 || batchExport.size() > 2) {
            log.info("Fetching confidence level values from Instrument files...");
            Long instruVerionId = 0L;
            Long structVerionId = 0L;
            Long optionsVerionId = 0L;

            for (BatchExport export : batchExport) {
                incrementProgress(export.getBatchExecutionId());
                Version version = export.getExportParams().getVersionOpt().orElseThrow(() -> new ReglissException("No version found in Regliss"));

                if (version.getImportFileType() == ImportFileType.SIX_INSTRUMENTS_FILE) {
                    instruVerionId = version.getId();
                }

                if (version.getImportFileType() == ImportFileType.SIX_STRUCTURED_FILE) {
                    structVerionId = version.getId();
                }

                if (version.getImportFileType() == ImportFileType.SIX_OPTIONS_FILE) {
                    optionsVerionId = version.getId();
                }
            }

            log.info("Started updating Structure file");
            int sfUpdated = structureFileRepository.bulkUpdateConfidenceFromInstrument(structVerionId, instruVerionId);
            log.info("Bulk-updated {} rows in STRUCTURED_FILE", sfUpdated);

            log.info("Started updating Options file");
            int optUpdated = optionsFileRepository.bulkUpdateConfidenceFromInstrument(optionsVerionId, instruVerionId);
            log.info("Bulk-updated {} rows in OPTIONS_FILE", optUpdated);

            for (BatchExport export : batchExport) {
                Version version = export.getExportParams().getVersionOpt().orElseThrow(() -> new ReglissException("No version found in Regliss"));
                Long batchExecutionId = export.getExportParams().getGeneratedFileIds().stream()
                        .findFirst().orElseThrow(() -> new ReglissException("No batchExecutionId found"));
                batchExportService.deleteProcessedBatchExport(export.getId());
                batchExportService.persistSixFilteredFileGenerationStubInTx(BatchExportType.SIX_FILTERED_FILE_GENERATION, GenerationReason.GENERATION, version);
                batchManagementService.updateBatchExecutionCompletedProgress(batchExecutionId);
                unblockImportService.finishSixImportExecutionByRequesterUser(batchExecutionId, export.getExportParams().getLauncherUsername());
            }
        }
    }

    public void incrementProgress(Long batchJobExecutionId) {
        batchProgressService.incrementExportBatchForSix(batchJobExecutionId, 3);
    }
}
