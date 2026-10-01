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
            sixFileRepo.deleteFiles(feed);

        } catch (RuntimeException e) {
            // Requirement: if any record fails to import, the entire job must fail
            log.error("{}: Could not import feed {} for list {}", importFileType, feed, list, e);
            feedService.saveErrorAndSendEmails(listId, e, feed);
            sixFileRepo.moveToErrorDirectory(feed);
        } finally {
            StaticContextAccessor.getBean(XsltApi.class).printAllReferentialsWarningMessages();
        }
    }

    private void importSixData(AutomaticImportFeedBase feed, ReglissList list, Long batchExecutionId) {
        Supplier<UnicodeBOMInputStream> xmlStreamProvider = openFromZipIfNecessary(feed, false);
        importService.doAutomaticSixImportFeed(feed, list, xmlStreamProvider, batchExecutionId);
    }
}
