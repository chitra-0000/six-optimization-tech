package com.bnpp.regliss.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

// TODO: re-add project imports (Alt+Enter / Optimize Imports) for:
// ReglissBatchProfile, CloseResourcesAfter, AutomaticImporter, ImportFileType,
// ThreadScopeContextHolder, StaticContextAccessor, XsltApi

@Component
@ReglissBatchProfile
@Slf4j
public class SixInstrumentImportPoller {

    @Autowired
    private AutomaticImporter importer;

    @Autowired
    private SixImportPoller sixImportPoller;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @CloseResourcesAfter
    @Scheduled(cron = "${task.dj_import.cron}")
    public Optional<Long> importSixInstrumentFiles() {
        try {
            if (sixImportPoller.processThreeFiles()) {
                log.debug("Started Six Instrument import");
                return importer.pollInputFolder(ImportFileType.SIX_INSTRUMENTS_FILE);
            }
            return Optional.empty();
        } finally {
            // Clears DJReferentialHolder
            ThreadScopeContextHolder.clearThread();
            StaticContextAccessor.getBean(XsltApi.class).printAllReferentialsWarningMessages();
            log.debug("Ended Six Instrument import");
        }
    }
}
