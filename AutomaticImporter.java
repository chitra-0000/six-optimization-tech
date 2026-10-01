package com.bnpp.regliss.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

@Slf4j
@ReglissBatchProfile
@Service
public class AutomaticImporter {

    @Autowired
    protected EmailService emailService;

    @Autowired
    protected ReglissRequestContext requestContext;

    @Autowired
    protected ReferenceService referenceService;

    @Autowired
    private BatchManagementService batchManagementService;

    @Autowired
    private ReglissListRepository reglissListRepository;

    @Autowired
    private AutomaticFeedAggregator feedAggregator;

    @Autowired
    private AutomaticFeedImporter feedImporter;

    @CloseResourcesAfter
    public Optional<Long> pollInputFolder(ImportFileType importFormat) {
        try {
            return importAutoFiles(importFormat);
        } finally {
            // Clears DJReferentialHolder
            ThreadScopeContextHolder.clearThread();
            //reports the INSERT commands to execute on DB to make the references link correctly.
            StaticContextAccessor.getBean(XsltApi.class).printAllReferentialsWarningMessages();
        }
    }

    public Optional<Long> importAutoFiles(ImportFileType importFileType) {
        //some methods used by UI will require a 'current user' on the current thread
        requestContext.setCurrentUser(referenceService.getSysUser());
        log.debug("Looking for new auto files");

        Map<Long, AutomaticImportFeedBase> importableFeeds = feedAggregator.getImportableFeedsByListIdInTx(importFileType);
        if (importableFeeds.isEmpty()) {
            return Optional.empty();
        }
        if (batchManagementService.currentNodeIsAllowedToStartImportAuto()) {
            Optional<Long> oneListIdOpt = importableFeeds.keySet().stream()
                    .filter(listId -> batchManagementService.attemptLockList(reglissListRepository.getExactlyOne(listId)))
                    .findFirst();

            if (oneListIdOpt.isPresent()) {
                Long listId = oneListIdOpt.get();
                log.info("list locked for import auto {} ", listId);
                if (importFileType.isSixRawFormat()) {
                    feedImporter.importSixFeedWithoutTx(listId, importableFeeds.get(listId), importFileType);
                } else {
                    feedImporter.importFeedWithoutTx(listId, importableFeeds.get(listId), importFileType);
                }
                return oneListIdOpt;
            }
        }
        return Optional.empty();
    }
}
