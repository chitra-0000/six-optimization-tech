package com.bnpp.regliss.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;          // jakarta.persistence.EntityManager on Spring Boot 3
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang3.StringUtils.isNotEmpty;   // TODO: check in the IDE (could be another StringUtils)

@Slf4j
@Service
public class VersionService {

    @Autowired
    private FileDeliveryService fileDeliveryService;

    @Autowired
    private IGeneratedFileService generatedFileService;

    @Autowired
    private VersionAuditService versionAuditService;

    @Autowired
    private LogRepository logRepository;

    @Autowired
    private VersionRepository versionRepo;

    @Autowired
    private ReglissListRepository reglissListRepo;

    @Autowired
    private AddModSupRepository exportAddModSupRepository;

    @Autowired
    private RecordRepository recordRepo;

    @Autowired
    private ReglissRequestContext requestContext;

    @Autowired
    private VersionLockService lockService;

    @Autowired
    private ReferenceService referenceService;

    @Autowired
    private SubscriberAuditService subscriberAuditService;

    @Autowired
    private BatchJobExecutionRepository batchJobExecutionRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ReglementationService reglementationService;

    @Autowired
    private EmailService emailService;

    @Autowired
    private InstrumentFileRepository instrumentFileRepository;

    @Autowired
    private StructureFileRepository structureFileRepository;

    @Autowired
    private OptionsFileRepository optionsFileRepository;

    @Autowired
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    private static final int BATCH_SIZE = 500;
    private static final String VERSION_ID_NOT_EQUAL_TO = "version_id <> :versionId";
    private static final String VERSION_ID_EQUAL_TO = "version_id = :versionId";

    public void deliveriesFinishedForVersion(Version version) {
        VersionStatus previousStatus = version.getStatus();
        VersionStatus newStatus = computeVersionStatus(version);
        version.deliver(newStatus);
        versionAuditService.auditDeliver(previousStatus, version);
    }

    public Version createVersion(Long listId) {
        ReglissList reglissList = reglissListRepo.getExactlyOne(listId);
        Version version = new Version();
        versionRepo.save(version);

        if (reglissList.getLastApplicableVersion().isPresent()) {
            Version previousVersion = reglissList.getLastApplicableVersion().orElseThrow(NoSuchElementException::new);
            version.setVersionNumber(previousVersion.getVersionNumber() + 1);
            version.setComputedRecordNumbers(new ComputedRecordNumbers());
            version.getComputedRecordNumbers().setTotal(previousVersion.getComputedRecordNumbers().getTotal());
        } else {
            version.setVersionNumber(1L);
        }

        version.create(requestContext.getCurrentUserRef());
        version.setImportFileType(reglissList.getImportFileType());
        reglissList.addVersion(version);
        log.info("Start linking the reglementations to version...");
        reglementationService.addListReglementationsToVersion(version, reglissList);
        log.info("Link done for version");
        versionAuditService.auditCreate(version);
        return version;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long createAutomaticVersionInTx(Long listId, AutomaticImportFeedBase feed) {
        log.debug("Creating new automatic version...");
        AutomaticImportFeedIdBase feedId = feed.getFeedId();
        Version version = createVersion(listId);
        if(feedId.getSourceVersion() !=  null && feed.isAuto()) {
            version.setVersionNumber(feedId.getSourceVersion());
        }

        version.setVersionDJ(new VersionDJ());

        if (isNotEmpty(feedId.getJuridictionBase())) {
            version.setLegalBase(feedId.getJuridictionBase());
        } else {
            version.setLegalBase("Group Compliance");
        }

        if (isNotEmpty(feedId.getDateStamp())) {
            version.setStartDate(FeedIdHelper.parseStartDate(feedId));
        }

        Long versionId = version.getId();
        log.info("Created new automatic version id: {} for list id:{}", versionId, listId);
        return versionId;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateApplicationDateForSemiAutomaticVersionInTx(Long versionId, AutomaticImportFeedBase feed){
        log.debug("Update ApplicationDate for custom manual version...");
        AutomaticImportFeedIdBase feedId = feed.getFeedId();
        Version versionInDb = versionRepo.getExactlyOne(versionId);
        Version oldVersion = new Version(versionInDb);
        if (isNotEmpty(feedId.getDateStamp())) {
            versionInDb.setStartDate(FeedIdHelper.parseStartDate(feedId));
        }
        versionAuditService.auditUpdate(oldVersion, versionInDb);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void integrateAutomaticVersionInTx(long versionId) {
        Version version = versionRepo.getExactlyOne(versionId);
        version.validate(requestContext.getCurrentUserRef());
        emailService.sendEmailValidationOk(version);
        log.info("Integrated automatic version id: {}", versionId);
        versionAuditService.auditIntegration(version);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteVersionAndRecordsInTx(Long versionId) {
        Version version = versionRepo.getExactlyOne(versionId);
        version.delete();
        //clear saved imported file from db
        version.getImportedFiles().stream()
                .forEach(importedFile -> importedFile.setFileContent(null));

        Optional<Version> previousVersionopt = version.getPreviousVersion();
        previousVersionopt.ifPresent(value -> recordRepo.revertRecordsToVersion(value.getId(),version.getList().getId()));
        //mark records created in version or cloned in version for purge
        recordRepo.markRecordsForPurgeFromVersion(versionId, version.getList().getId());

        log.info("Deleted version and associated records for version id: {}", versionId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteVersionAndRecordsFromDBInTx(Long versionId) {
        log.info("Deleting version and records links");
        //revert deletedInVersion, toVersion values to previous version values (null, DEFAULT_VALUE) for previous version
        Version version = versionRepo.getExactlyOne(versionId);
        Optional<Version> previousVersionOpt = version.getPreviousVersion();
        previousVersionOpt.ifPresent(value -> recordRepo.revertRecordsToVersion(value.getId(), version.getList().getId()));
        //mark records created in version or cloned in version for purge
        recordRepo.markRecordsForPurgeFromVersion(versionId, version.getList().getId());

        versionAuditService.deleteAllAuditsForVersionFromDB(versionId);
        logRepository.deleteLogByVersion(versionId);
        entityManager.flush();
        batchJobExecutionRepository.findByVersion(version)
            .forEach(BatchJobExecution::removeVersion);
        version.removeAlladdModSupAuditTrailFiles();
        versionRepo.deleteVersion(versionId);
        log.debug("Ending delete version and records links");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revertVersionInTx(Long versionId) {
        Version version = versionRepo.getExactlyOne(versionId);
        revertVersionStatistics(version);
        ReglissList reglissList = version.getList();
        Optional<Version> lastApplicableVersion = reglissList.getLastApplicableVersion();
        if (lastApplicableVersion.isPresent()) {
            Version previousVersion = lastApplicableVersion.get();
            version.setVersionNumber(previousVersion.getVersionNumber() + 1);
            version.getComputedRecordNumbers().setTotal(previousVersion.getComputedRecordNumbers().getTotal());
            //revert records from previous versions
            recordRepo.revertRecordsToVersion(previousVersion.getId(),reglissList.getId());
            //mark records created in version or cloned in version for purge
            recordRepo.markRecordsForPurgeFromVersion(versionId, reglissList.getId());
        }else {
            recordRepo.markRecordsForPurgeFromVersion(versionId, reglissList.getId());
            version.getComputedRecordNumbers().setTotal(0L);
        }

        versionAuditService.deleteAllAuditsForTheRecordsOfVersion(versionId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revertVersionInTx(Long versionId,Integer versionDeletetimeout) {
        Version version = versionRepo.getExactlyOne(versionId);
        revertVersionStatistics(version);
        ReglissList reglissList = version.getList();
        Optional<Version> lastApplicableVersion = reglissList.getLastApplicableVersion();
        if (lastApplicableVersion.isPresent()) {
            Version previousVersion = lastApplicableVersion.get();
            version.setVersionNumber(previousVersion.getVersionNumber() + 1);
            version.getComputedRecordNumbers().setTotal(previousVersion.getComputedRecordNumbers().getTotal());
            //revert records from previous versions
            recordRepo.revertRecordsToVersion(previousVersion.getId(),reglissList.getId(),versionDeletetimeout);
            //mark records created in version or cloned in version for purge
            recordRepo.markRecordsForPurgeFromVersion(versionId, reglissList.getId(),versionDeletetimeout);
        }else {
            recordRepo.markRecordsForPurgeFromVersion(versionId, reglissList.getId(),versionDeletetimeout);
            version.getComputedRecordNumbers().setTotal(0L);
        }

        versionAuditService.deleteAllAuditsForTheRecordsOfVersion(versionId);
    }

    /**
     Set version computed statistics back to 0
     @param version the version to be reverted
     */
    private void revertVersionStatistics(Version version) {
        if (version.getComputedRecordNumbers()==null) {
            version.setComputedRecordNumbers(new ComputedRecordNumbers());
        }
        version.getComputedRecordNumbers().setAdd(0L);
        version.getComputedRecordNumbers().setMod(0L);
        version.getComputedRecordNumbers().setSup(0L);
        version.getComputedRecordNumbers().setAddPersons(0L);
        version.getComputedRecordNumbers().setModPersons(0L);
        version.getComputedRecordNumbers().setSupPersons(0L);
    }

    @Data
    public static class FileIdsToGenerate {

        private final List<Long> exportFileIds;
        private final Long addModSupFileId;

        public FileIdsToGenerate(List<Long> exportFilesStubsIds) {
            this(exportFilesStubsIds, null);
        }

        public FileIdsToGenerate(List<Long> exportFilesStubsIds, Long addModSupFileId) {
            this.exportFileIds = exportFilesStubsIds;
            this.addModSupFileId = addModSupFileId;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FileIdsToGenerate certifyVersionInTx(long versionId, String confirmationMessage) {
        if (recordRepo.countByVersion(versionId) == 0) {
            throw new ReglissException(ErrorCode.EMPTY_VERSION_CANNOT_BE_CERTIFIED);
        }
        Version version = versionRepo.getExactlyOne(versionId);
        lockService.checkAndExtendLock(version);
        if (version.getList().allowsAutoUpload()) {
            version.confirm(requestContext.getCurrentUserRef());
            versionAuditService.auditConfirm(version, confirmationMessage);
        } else {
            version.certify(requestContext.getCurrentUserRef());
            versionAuditService.auditCertify(version);
        }
        if(version.getList().getEnabledFileFormats().isEmpty()){
            version.autoPublish();
            log.info("Updating version {} status after auto publish to {} at:{}",versionId, version.getStatus(),version.getPublicationDate());
            versionAuditService.auditPublish(version, referenceService.getSysUser());
        }

        return persistFileStubsToGenerate(version);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void integrateVersionInSix(long versionId) {
        Version version = versionRepo.getExactlyOne(versionId);
        version.validate(requestContext.getCurrentUserRef());
        log.info("Integrated automatic version id: {}", versionId);
        versionAuditService.auditIntegration(version);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void certifyVersionInSIX(long versionId) {
        Version version = versionRepo.getExactlyOne(versionId);
        lockService.checkAndExtendLock(version);
        version.certify(requestContext.getCurrentUserRef());
        log.info("Certified automatic version id: {}", versionId);
        versionAuditService.auditCertify(version);
    }

    private FileIdsToGenerate persistFileStubsToGenerate(Version version) {
        List<GeneratedFile> generatedFiles = generatedFileService.createGeneratedFileStubsAfterCertification(version);
        List<Long> exportFilesStubsIds = generatedFiles.stream().map(AbstractSimpleEntity::getId).collect(toList());
        if(versionIsEligibleForAddModSupExportFile(version.getId())) {
            AddModSupExportFile addModSupFile = generatedFileService.createAddModSupFileStub(version);
            return new FileIdsToGenerate(exportFilesStubsIds, addModSupFile.getId());
        }
        return new FileIdsToGenerate(exportFilesStubsIds);
    }

    @Transactional(propagation= Propagation.REQUIRES_NEW)
    public boolean versionIsEligibleForAddModSupExportFile(Long versionId) {
        Version version = versionRepo.getExactlyOne(versionId);

        return version.wasCertified() &&
                version.getImportFileType() != ImportFileType.NOT_SUPPORTED;
    }

    @Transactional(propagation= Propagation.REQUIRES_NEW)
    public boolean versionIsEligibleForAddModSupAuditTrailFile(Long versionId) {
        Version version = versionRepo.getExactlyOne(versionId);

        return  ( (!version.wasAutoUploaded() && version.wasValidated()) ||
                  (version.wasAutoUploaded() && version.wasCertified()) )     &&
                version.getImportFileType() != ImportFileType.NOT_SUPPORTED;
    }

    public VersionStatus computeVersionStatus(Version version) {
        if (version.statusIsOneOf(VersionStatus.DRAFT, VersionStatus.VALIDATED, VersionStatus.RETURNED, VersionStatus.DELETED)
                || generatedFileService.getLatestGeneratedFilesForVersion(version.getId()).stream().anyMatch(GeneratedFile::isInProgress)) {
            return version.getStatus();
        }
        List<GeneratedFile> latestFiles = generatedFileService.getLatestGeneratedFilesForVersion(version.getId());
        VersionStatus generationStatus = getGenerationStatus(latestFiles);

        if (generationStatus == VersionStatus.PUBLISHED_KO) {
            return generationStatus;
        } else {
            List<FileDelivery> latestDeliveries = fileDeliveryService.getLatestDeliveriesForVersion(version);
            if (latestDeliveries.isEmpty() || latestDeliveries.stream().anyMatch(FileDelivery::isInProgress)) {
                return generationStatus;
            } else if (latestDeliveries.stream().anyMatch(FileDelivery::isKo)) {
                return VersionStatus.DELIVERED_KO;
            } else {
                return VersionStatus.DELIVERED_OK;
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishFinishedForVersionInTx(long versionId, LocalDateTime publicationDate, Set<GeneratedFile> generatedOk) {
        Version version = versionRepo.getExactlyOne(versionId);
        VersionStatus newStatus = computeVersionStatus(version);
        version.publish(newStatus, publicationDate);
        log.info("Updating version {} status after publish to {} at:{}",versionId, newStatus, publicationDate);
        versionAuditService.auditPublish(version, publicationDate);

        auditDeliveryReportGeneration(generatedOk);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishFinishedDeliveryReportsTx( Set<GeneratedFile> generatedOk) {
        auditDeliveryReportGeneration(generatedOk);
    }

    private void auditDeliveryReportGeneration(Set<GeneratedFile> generatedOk) {
        List<GeneratedFile> deliveryReports = generatedOk.stream()
                .filter(gf -> gf.getGeneratedFileFormat().getFileFormat() == FileExportFormat.DELIVERY_REPORT)
                .collect(toList());
        for (GeneratedFile generatedFile : deliveryReports) {
            subscriberAuditService.auditDeliveryReportGeneration(generatedFile);
        }
    }

    private VersionStatus getGenerationStatus(List<GeneratedFile> latestFiles) {
        if (latestFiles.stream().anyMatch(GeneratedFile::isKO)) {
            return VersionStatus.PUBLISHED_KO;
        } else {
            return VersionStatus.PUBLISHED_OK;
        }
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void writeCountRecordNumbers(long versionId ) {
        Version version = versionRepo.getExactlyOne(versionId);
        VersionStatistics vs = recordRepo.countVersionStatistics(versionId,version.getList().getId());
        int countModRecordsForSynthesys = exportAddModSupRepository.getCountModifiedRecordsForSynthesis(versionId);
        ComputedRecordNumbers computedRecordNumbers = new ComputedRecordNumbers(vs.getTotalRecords(),
                    vs.getCreatedRecords(),
                    vs.getUpdatedRecords(),
                    countModRecordsForSynthesys,
                    vs.getDeletedRecords(),
                    vs.getStrongRecords(),
                    vs.getWeakRecords());
        VersionPersonStatistics versionPersonStatistics=vs.getVersionPersonStatistics();
        computedRecordNumbers.setAddPersons(versionPersonStatistics.getCreatedPersons());
        computedRecordNumbers.setModPersons(versionPersonStatistics.getUpdatedPersons());
        computedRecordNumbers.setSupPersons(versionPersonStatistics.getDeletedPersons());
        version.setComputedRecordNumbers(computedRecordNumbers);
    }

    //ASAP REGV4-51
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeLogRecordNumbers(long versionId, LogRecordNumbers logRecordNumbers) {
        VersionDJ versionDj = versionRepo.getExactlyOne(versionId).getVersionDJ();
        versionDj.setLogRecordNumbers(logRecordNumbers);
    }

    @Transactional(propagation = Propagation.SUPPORTS)
    public List<Version> getAllOtherLatestPublishedVersions(Long excludedListId) {
        return versionRepo.getAllOtherLatestPublishedVersions(excludedListId);
    }

    public boolean djImportLogFileControl(long versionId){
        Version version = versionRepo.getExactlyOne(versionId);
        return version.getVersionDJ().getLogRecordNumbers().getTotal() == version.getComputedRecordNumbers().getTotal();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteVersionAndRecordsFromDBInSix(Long versionId) {
        log.info("Deleting six version and records");
        Version version = versionRepo.getExactlyOne(versionId);
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("versionId", versionId);

        ImportFileType filetype = version.getImportFileType();
        if (filetype.isSIXINSTRUMENTSFileType()) {
            batchDelete("six_instruments", VERSION_ID_EQUAL_TO, params);
        } else if (filetype.isSIXStructuredFileType()) {
            batchDelete("six_structured", VERSION_ID_EQUAL_TO, params);
        } else if (filetype.isSIXOptionsFileType()) {
            batchDelete("SIX_OPTION", VERSION_ID_EQUAL_TO, params);
        }

        batchJobExecutionRepository.findByVersion(version)
                .forEach(BatchJobExecution::removeVersion);
        versionRepo.deleteVersion(versionId);
        log.debug("Ending delete six version and records");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteNonLatestSixRecordsFromDB(Long versionId) {
        log.info("Deleting old version of six records");
        Version version = versionRepo.getExactlyOne(versionId);
        ImportFileType filetype = version.getImportFileType();
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("versionId", versionId);

        if (filetype.isSIXINSTRUMENTSFileType()) {
            int countVersions = instrumentFileRepository.getTotalNumberOfVersionIds();
            if (countVersions > 1) {
                batchDelete("six_instruments", VERSION_ID_NOT_EQUAL_TO, params);
            }
        } else if (filetype.isSIXStructuredFileType()) {
            int countVersions = structureFileRepository.getTotalNumberOfVersionIds();
            if (countVersions > 1) {
                batchDelete("six_structured", VERSION_ID_NOT_EQUAL_TO, params);
            }
        } else if (filetype.isSIXOptionsFileType()) {
            int countVersions = optionsFileRepository.getTotalNumberOfVersionIds();
            if (countVersions > 1) {
                batchDelete("SIX_OPTION", VERSION_ID_NOT_EQUAL_TO, params);
            }
        }
    }

    private void batchDelete(String tableName, String whereClause, MapSqlParameterSource params) {
        String sql = "DELETE FROM " + tableName + " WHERE " + whereClause + " AND ROWNUM <= :batchSize";
        params.addValue("batchSize", BATCH_SIZE);

        int affectedRows;
        int totalDeleted = 0;
        do {
            affectedRows = namedParameterJdbcTemplate.update(sql, params);
            totalDeleted += affectedRows;
            log.debug("Batch deleted {} rows from {}. Total so far: {}", affectedRows, tableName, totalDeleted);
        } while (affectedRows == BATCH_SIZE);

        log.info("Finished batch deletion from {}. Total rows removed: {}", tableName, totalDeleted);
    }
}
