package com.bnpp.regliss.importer.dj;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ReglissBatchProfile
@Slf4j
public class VersionPostDJImportService {

    @Autowired
    private VersionRepository versionRepo;

    @Autowired
    private RecordRepository recordRepo;

    @Autowired
    private EmailService emailService;

    @Value("${automatic.import.autoconfirm.threshold.percentage}")
    long autoConfirmDeltaThresholdPercentage;

    @Autowired
    private VersionService versionService;

    @Autowired
    private BatchExportService batchExportService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void postProcessVersionBasedOnDeltaInTx(long versionId) {
        log.info("Post-process version id {} based on delta", versionId);
        Version version = versionRepo.getExactlyOne(versionId);
        double deltaPercentage = computeDeltaPercentage(version);
        if (deltaPercentage < 0.0001d && !ReglissEnvironment.isLocalDev()) {
            log.debug("Auto-deleting version id={} identical to the previous version ", versionId);
            versionService.deleteVersionAndRecordsInTx(versionId);
            emailService.sendEmailDjImportDeltaPurcentage(version);
        } else if (version.getList().getImportConfiguration().isConfirmationRequired()) {
            log.debug("Sending mail with confirmation required for version id={}", versionId);
            emailService.sendEmailDjImportConfirmationRequired(version);
        } else if (canAutoConfirm(version, deltaPercentage)) {
            log.debug("Auto-confirming version id={}", versionId);
            FileIdsToGenerate filesToGenerate = versionService.certifyVersionInTx(versionId, "Auto-confirmed");
            log.info("Updated version {} status after auto confirmation",versionId);
            if (!ReglissEnvironment.isJUnitTest()) {
                batchExportService.persistVersionExportGenerationAtCertificationOrConfirmation(versionId, filesToGenerate);
            }
        } else {
            log.debug("Sending email for manual confirmation version id={}", versionId);
            emailService.sendEmailDjImportConfirmation(version);
        }
    }

    private double computeDeltaPercentage(Version version) {
        if (!version.getPreviousVersion().isPresent()) {
            return Double.MAX_VALUE;
        }
        log.info("Computing delta percentage...");

        long updatedCount = runAndMeasure("UpdatedCount", () -> recordRepo.countUpdatedRecordsForVersion(version.getId(), version.getList().getId()));
        long deletedCount = runAndMeasure("DeletedCount", () -> recordRepo.countDeletedRecordsForVersion(version.getId(), version.getList().getId()));
        long createdCount = runAndMeasure("CreatedCount", () -> recordRepo.countCreatedRecordsForVersion(version.getId(), version.getList().getId()));
        long changedCount = updatedCount + deletedCount + createdCount;

        long totalCount = recordRepo.countByVersion(version.getId());

        double deltaPercentage = 100.0 * changedCount / totalCount;
        log.info("Delta for version id={}, delta = {}% (CRE:{}, UPD:{}, DEL:{}, TOTAL:{}). Threshold: {}%",
                version.getId(), String.format("%.2f", deltaPercentage),
                createdCount, updatedCount, deletedCount, totalCount,
                autoConfirmDeltaThresholdPercentage);
        return deltaPercentage;
    }

    private boolean canAutoConfirm(Version version, double deltaPercentage) {
        if (!version.getPreviousVersion().isPresent()) {
            log.info("Auto-confirming version because it's the first version of this list");
            return true;
        } else {
            long threshold = version.getList().getImportConfiguration().getAutoConfirmationThreshold() != null ?
                    version.getList().getImportConfiguration().getAutoConfirmationThreshold(): autoConfirmDeltaThresholdPercentage;
            return deltaPercentage <= threshold;
        }
    }

    public void postProcessVersionSixRaw(Long versionId, long batchExecutionId) {
        Version version = versionRepo.getExactlyOne(versionId);
        versionService.integrateVersionInSix(versionId);
        versionService.certifyVersionInSIX(versionId);
        batchExportService.persistSixImportedFileGenerationStubInTx(BatchExportType.SIX_CONFIDENCE_VALUES, GenerationReason.GENERATION, version, batchExecutionId);
    }
}
