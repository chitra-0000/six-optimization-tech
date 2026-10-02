package com.bnpp.regliss.importer.dj;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Supplier;

@Service
@ReglissBatchProfile
@Slf4j
public class ImportService {

    @Autowired
    private AutomaticImportXmlService automaticImportXmlService;

    @Autowired
    private VersionPostDJImportService versionPostImportService;

    @Autowired
    private EmailService emailService;

    @Autowired
    private DJImportCsvService importCsvService;

    @Autowired
    private VersionLockService versionLockService;

    @Autowired
    private VersionService versionService;

    @Autowired
    private AutomaticImportFileRepository automaticImportFileRepository;

    @Autowired
    private ManualImportFileRepository manualImportFileRepository;

    @Autowired
    private ImportedFileService importedFileService;

    @Autowired
    private DJLogLivraisonService logLivraisonService;

    @Autowired
    private BatchManagementService batchManagementService;

    @Autowired
    private UnblockImportService unblockImportService;

    @Autowired
    private LastModificationDateCalculationService lastModificationDateCalculationService;

    @Autowired
    private AutomaticSixImportFileRepository automaticSixImportFileRepository;

    @Autowired
    private AutomaticSixImportXmlService automaticSixImportXmlService;

    private static final String ALL_DONE_FOR_VERSION = "All done for version id:{}";
    private static final String BATCH_FAIL_MESSAGE = "Batch failed with message {}";
    private static final String BATCH_FAIL_ERROR = "Batch failed with error"; // TODO: value hidden by a tooltip in the photo - copy it from the IDE


    @CloseResourcesAfter
    public Long doAutomaticImportFeed(AutomaticImportFeedBase feed, ReglissList list,
                                      Supplier<UnicodeBOMInputStream> xmlStreamProvider,
                                      Supplier<UnicodeBOMInputStream> csvStreamProvider,
                                      long batchExecutionId) {
        ImportFileType format = list.getImportConfiguration().getImportFileType();
        Long versionId = versionService.createAutomaticVersionInTx(list.getId(), feed);
        batchManagementService.updateBatchExecutionVersionInTx(batchExecutionId, versionId);
        versionLockService.lockAutomaticVersionInTx(versionId);

        try {
            // THE import of XML
            automaticImportXmlService.importAutomaticFileWithoutTx(xmlStreamProvider, format, feed, versionId, batchExecutionId, list.getImportConfiguration());
            if (format.isDjFormat()) {
                if (csvStreamProvider != null) {
                    importCsvService.importDowJonesCsv(csvStreamProvider, versionId, list.getId(), batchExecutionId, list.getImportConfiguration());
                }
                log.debug("processing version DJ log numbers {}", versionId);
                Optional<LogRecordNumbers> logRecordNumbers = getLogRecordNumbers((DJFeed) feed);
                logRecordNumbers.ifPresent(recordNumbers -> versionService.writeLogRecordNumbers(versionId, recordNumbers));
            }

            log.debug("Start computing version statistics {}", versionId);
            versionService.writeCountRecordNumbers(versionId);
            log.debug("Statistics saved for version {}", versionId);

            djImportLogFileControl(feed, format, versionId);

            importedFileService.saveImportedFileInTx(getInputFileByName(feed.isAuto(), feed.getXmlDataFileName()), feed.getXmlDataFileName(), versionId, null);

            versionService.integrateAutomaticVersionInTx(versionId);

            emailService.sendEmailDjImportSuccess(versionId);
            versionPostImportService.postProcessVersionBasedOnDeltaInTx(versionId);
            lastModificationDateCalculationService.updateLastModificationDate(versionId, list.getId());



            log.info(ALL_DONE_FOR_VERSION, versionId);
            return versionId;
        } catch (RuntimeException e) {
            log.error(BATCH_FAIL_MESSAGE, e.getMessage());
            log.debug(BATCH_FAIL_ERROR, e);
            emailService.sendEmailImportError(e.getMessage() ,  new ListIdAndVersionId(list.getId(), versionId));
            unblockImportService.tryDeleteAutomaticVersionAndRecordsInTx(versionId);
            throw e;
        } finally {
            versionLockService.unlockAutomaticVersionInTx(versionId);
        }
    }

    @CloseResourcesAfter
    public Long doManualImportFeed(AutomaticImportFeedBase feed, ReglissList list,
                                   Supplier<UnicodeBOMInputStream> xmlStreamProvider,
                                   long batchExecutionId, Long importedFileId) {
        ImportFileType format = list.getImportConfiguration().getImportFileType();
        Long versionId = list.getLastVersion().map(Version::getId).orElseThrow(NoSuchElementException::new);

        batchManagementService.updateBatchExecutionVersionInTx(batchExecutionId, versionId);
        versionLockService.lockSemiAutoVersionInTx(versionId);
        try {
            // THE import of XML
            automaticImportXmlService.importAutomaticFileWithoutTx(xmlStreamProvider, format, feed, versionId, batchExecutionId, list.getImportConfiguration());

            log.debug("Start computing version statistics {}", versionId);
            versionService.writeCountRecordNumbers(versionId);
            log.debug("Statistics saved for version {}", versionId);
            versionService.updateApplicationDateForSemiAutomaticVersionInTx(versionId, feed);
            importedFileService.saveImportedFileInTx(getInputFileByName(feed.isAuto(), feed.getXmlDataFileName()), feed.getOriginalFileName(), versionId, importedFileId);
            emailService.sendEmailDjImportSuccess(versionId);
            lastModificationDateCalculationService.updateLastModificationDate(versionId, list.getId());

            log.info(ALL_DONE_FOR_VERSION, versionId);
            return versionId;
        } catch (RuntimeException e) {
            log.error(BATCH_FAIL_MESSAGE, e.getMessage());
            log.debug(BATCH_FAIL_ERROR, e);
            emailService.sendEmailImportError(e.getMessage(), new ListIdAndVersionId(list.getId(), versionId));

            throw e;
        }finally {
            versionLockService.unlockSemiAutoVersionInTx(versionId);
        }
    }

    private void djImportLogFileControl(AutomaticImportFeedBase feed, ImportFileType format, Long versionId) {
        if (format.isDjFormat()                   &&
            feed.getFeedId().isFull()             &&
            null != ((DJFeed) feed).getXmlLogFileName() &&
            !versionService.djImportLogFileControl(versionId)){

                emailService.sendEmailDJImportLogFileControlKO(versionId);
                throw new ReglissException("Log control failed");
        }
    }

    private Optional<LogRecordNumbers> getLogRecordNumbers(DJFeed feed) {
        String xmlLogFileName = feed.getXmlLogFileName();
        if (null != xmlLogFileName) {
            File logFile = getInputFileByName(feed.isAuto(), xmlLogFileName);

            if (null != logFile) {
                DjLogLivrasionStatistics djLogLivrasionStatistics = this.logLivraisonService.getValueFromLog(logFile);
                return Optional.of(new LogRecordNumbers(djLogLivrasionStatistics.getTotalRecords(), djLogLivrasionStatistics.getAddRecords(),
                        djLogLivrasionStatistics.getModRecords(), djLogLivrasionStatistics.getSupRecords(),
                        djLogLivrasionStatistics.getAddEntities(),djLogLivrasionStatistics.getAddPersons(),djLogLivrasionStatistics.getModEntities(),djLogLivrasionStatistics.getModPersons(),
                        djLogLivrasionStatistics.getSupEntities(),djLogLivrasionStatistics.getSupPersons(),djLogLivrasionStatistics.getUploadDate()));

            }
        }
        return Optional.empty();
    }
    private File getInputFileByName(boolean isAuto, String fileName){
        if(isAuto){
            return automaticImportFileRepository.getInputFileByName(fileName);
        }else{
            return manualImportFileRepository.getFileInUploadFolderByName(fileName);
        }

    }

    @CloseResourcesAfter
    public Long doAutomaticSixImportFeed(AutomaticImportFeedBase feed, ReglissList list, Supplier<UnicodeBOMInputStream> xmlStreamProvider, Long batchExecutionId) {
        Long versionId = versionService.createAutomaticVersionInTx(list.getId(), feed);
        batchManagementService.updateBatchExecutionVersionInTx(batchExecutionId, versionId);
        versionLockService.lockAutomaticVersionInTx(versionId);

        try {
            automaticSixImportXmlService.importAutomaticFileWithoutTx(xmlStreamProvider, list, versionId, batchExecutionId);
            importedFileService.saveImportedFileInTx(automaticSixImportFileRepository.getInputFileByName(feed.getXmlDataFileName()),feed.getXmlDataFileName(), versionId, null);
            versionPostImportService.postProcessVersionSixRaw(versionId, batchExecutionId);

            log.info(ALL_DONE_FOR_VERSION, versionId);
            return versionId;
        } catch (RuntimeException e) {
            log.error(BATCH_FAIL_MESSAGE, e.getMessage());
            log.debug(BATCH_FAIL_ERROR, e);
            emailService.sendEmailImportError(e.getMessage() ,  new ListIdAndVersionId(list.getId(), versionId));
            unblockImportService.tryDeleteSixAutomaticVersionAndRecordsInTx(versionId);
            throw e;
        } finally {
            versionLockService.unlockAutomaticVersionInTx(versionId);
//            unblockImportService.tryDeleteNonLatestSixVersionRecords(versionId);
        }
    }
}
