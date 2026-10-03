package com.bnpp.regliss.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

@Service
@ReglissBatchProfile
@Slf4j
public class AutomaticFeedImporter extends FeedImporter{

    @Autowired
    private ReglissListRepository listRepo;

    @Autowired
    private BatchManagementService batchManagementService;

    @Autowired
    private FeedValidator feedValidator;

    @Autowired
    private AutomaticImportFileRepository djFileRepo;

    @Autowired
    private AutomaticFeedService feedService;

    @Autowired
    private UnblockImportService unblockImportService;

    @Autowired
    private ImportService importService;

    @Autowired
    private AutomaticSixImportFileRepository sixFileRepo;

    @Autowired
    private SixDeliveryService sixDeliveryService;   // TODO: import com.bnpp.regliss.importer.six.service.SixDeliveryService

    public void importFeedWithoutTx(Long listId, AutomaticImportFeedBase feed, ImportFileType importFileType) {
        ReglissList list = listRepo.findByIdFetchVersions(listId);
        Long batchExecutionId = null;

        try {
            log.info("Processing feed {}", feed);
            batchExecutionId = batchManagementService.createNewAutoBatchExecutionInTx(feed, list);

            feedValidator.checkUTF8InvalidFeedFiles(feed, new ListIdAndVersionId(listId, null));

            if (feed.isCustomAutomatic()) {
                insertXmlValuesIntoCAFeed((CustomAutomaticFeed) feed);
            }

            feedValidator.verifyFeed(list, feed, batchExecutionId);
            importData(feed, list, batchExecutionId);

            feedService.removeFromListPreviousErrors(listId, feed);
            djFileRepo.deleteFiles(feed);
            batchManagementService.updateBatchExecutionCompletedProgress(batchExecutionId);

        } catch (RuntimeException e) {
            // Requirement: if any record fails to import, the entire job must fail
            log.error("{}: Could not import feed {} for list {}", importFileType, feed, list, e);
            feedService.saveErrorAndSendEmails(listId, e, feed);
            djFileRepo.moveToErrorDirectory(feed);
        } finally {
            StaticContextAccessor.getBean(XsltApi.class).printAllReferentialsWarningMessages();
            unblockImportService.finishImportExecutionByRequesterUser(batchExecutionId);
        }
    }

    private void importData(AutomaticImportFeedBase feed, ReglissList list, long batchExecutionId) {
        Supplier<UnicodeBOMInputStream> xmlStreamProvider = openFromZipIfNecessary(feed, false);
        Supplier<UnicodeBOMInputStream> csvStreamProvider = null;
        if (feed.isDj() && list.getImportConfiguration().isWatchlist()) {
            csvStreamProvider = openFromZipIfNecessary(feed, true);
        }
        importService.doAutomaticImportFeed(feed, list, xmlStreamProvider, csvStreamProvider, batchExecutionId);
    }

    public void importSixFeedWithoutTx(Long listId, AutomaticImportFeedBase feed, ImportFileType importFileType) {
        ReglissList list = listRepo.findByIdFetchVersions(listId);
        Long batchExecutionId = null;

        try {
            log.info("Processing feed {}", feed);
            batchExecutionId = batchManagementService.createNewSixRawAutoBatchExecutionInTx(feed, list);

            feedValidator.checkUTF8InvalidFeedFiles(feed, new ListIdAndVersionId(listId, null));

            feedValidator.verifyFeed(list, feed, batchExecutionId);
            importSixData(feed, list, batchExecutionId);

            feedService.removeFromListPreviousErrors(listId, feed);
            // SIX delivery (all or nothing): the file is NOT deleted here any more. It stays in IN, its list
            // stays locked, until the whole delivery is finished (SixBatchConfidencePoller deletes it) or
            // rolled back (moved to ERROR). If the delivery was cancelled meanwhile, this file is rolled back now.
            sixDeliveryService.afterSuccessfulImport(feed);

        } catch (RuntimeException e) {
            // Requirement: if any record fails to import, the entire job must fail
            log.error("{}: Could not import feed {} for list {}", importFileType, feed, list, e);
            feedService.saveErrorAndSendEmails(listId, e, feed);
            // "IfPresent": if another server's rollback already moved this file, the copy in ERROR is kept
            // (moveToErrorDirectory would delete it when the source is gone).
            try {
                feed.getAllFileNames().forEach(sixFileRepo::moveToErrorDirectoryIfPresent);
            } catch (RuntimeException moveError) {
                log.error("Could not move {} to the error folder", feed.getAllFileNames(), moveError);
            }
            // ... and the whole SIX delivery with it: the other files are rolled back / not imported, all go to ERROR,
            // this job is closed and every list of the delivery is unlocked.
            sixDeliveryService.afterFailedImport(feed, listId, batchExecutionId, e);
        } finally {
            StaticContextAccessor.getBean(XsltApi.class).printAllReferentialsWarningMessages();
        }
    }

    private void importSixData(AutomaticImportFeedBase feed, ReglissList list, Long batchExecutionId) {
        Supplier<UnicodeBOMInputStream> xmlStreamProvider = openFromZipIfNecessary(feed, false);
        importService.doAutomaticSixImportFeed(feed, list, xmlStreamProvider, batchExecutionId);
    }
}
