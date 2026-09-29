package com.bnpp.regliss.importer.six.extractor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

// TODO: the project imports were collapsed ("import ...") in the screenshots.
// Re-add them in the IDE (Alt+Enter / Optimize Imports) for:
// ReglissBatchProfile, CloseResourcesAfter, BatchExport, Version, ReglissList, ReglissException,
// SixFilters, InstrumentFile, StructuredFile, OptionsFile, SixFilteredPoller,
// SixFiltersRepository, ReglissListRepository, InstrumentFileRepository, StructureFileRepository,
// OptionsFileRepository, SixFileFilterService, FilteredDataMapper, SixFilteredPollerRepository,
// FilteredInstrumentFileImportService, FilteredStructuredFileImportService, FilteredOptionsFileImportService,
// FilteredInstrumentFileRepository, FilteredStructureFileRepository, FilteredOptionsFileRepository,
// FilteredSixTargetRepository, BatchProgressService

@Component
@ReglissBatchProfile
@Slf4j
public class SixFilterExtractExport {
    @Autowired
    private SixFiltersRepository sixFiltersRepository;
    @Autowired
    private ReglissListRepository reglissListRepository;
    @Autowired
    private InstrumentFileRepository instrumentFileRepository;
    @Autowired
    private StructureFileRepository structureFileRepository;
    @Autowired
    private OptionsFileRepository optionsFileRepository;
    @Autowired
    private SixFileFilterService sixFileFilterService;
    @Autowired
    private FilteredDataMapper filteredDataMapper;
    @Autowired
    private SixFilteredPollerRepository sixFilteredPollerRepository;
    @Autowired
    private FilteredInstrumentFileImportService filteredInstrumentFileImportService;
    @Autowired
    private FilteredStructuredFileImportService filteredStructuredFileImportService;
    @Autowired
    private FilteredOptionsFileImportService filteredOptionsFileImportService;
    @Autowired
    private FilteredInstrumentFileRepository filteredInstrumentFileRepository;
    @Autowired
    private FilteredStructureFileRepository filteredStructureFileRepository;
    @Autowired
    private FilteredOptionsFileRepository filteredOptionsFileRepository;
    @Autowired
    private FilteredSixTargetRepository filteredSixTargetRepository;
    @Autowired
    private BatchProgressService batchProgressService;

    @Value("${six.exclusion.list}")
    private String sixExclusionList;
    @Value("${six.cmic.list}")
    private String sixCMICList;
    @Value("${six.e014071.list}")
    private String sixE014071List;
    @Value("${six.db.batch}")
    private int dbBatchSize;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @CloseResourcesAfter
    public void filteringSixRawFiles(BatchExport batchExport) {
        log.info("Filtering six list files: {}", batchExport);
        Version versionRaw = batchExport.getExportParams().getVersionOpt().orElseThrow(() -> new ReglissException("Version not found"));
        ReglissList listRaw = versionRaw.getList();
        List<String> listReference = batchExport.getExportParams().getGeneratedFileIds().stream().map(String::valueOf).collect(Collectors.toList());
        List<ReglissList> lists = reglissListRepository.findByListRef(listReference);
        List<SixFilters> cmicSixFilters = sixFiltersRepository.getFiltersByListRefAndActiveTrueAndDeletedFalse(sixCMICList);
        List<SixFilters> e014071SixFilters = sixFiltersRepository.getFiltersByListRefAndActiveTrueAndDeletedFalse(sixE014071List);

        double percent = 72.0 / lists.size();
        incrementProgress(batchExport.getBatchExecutionId(), 9);
        for (ReglissList list : lists) {
            List<SixFilters> sixFilters = sixFiltersRepository.getFiltersByListIdAndActiveTrueAndDeletedFalse(list.getId());
            log.info("six filters size : {}, list ref : {}", sixFilters.size(), list.getReference());

            if (listRaw.getImportFileType().isSIXINSTRUMENTSFileType()) {
                List<InstrumentFile> includedEntries = sixFileFilterService.filterInstrument(sixFilters, listRaw.getId(), versionRaw.getId(), cmicSixFilters, e014071SixFilters,
                        batchExport.getBatchExecutionId(), percent / 1.5);
                log.info("Filtered Instrument size : {}, list ref : {}", includedEntries.size(), list.getReference());

                filteredInstrumentFileRepository.deleteInstrumentBatch(versionRaw.getId(), list.getReference(), dbBatchSize);
                log.info("Deleted filtered instrument data in db of current version {}, if exists", versionRaw.getId());
                incrementProgress(batchExport.getBatchExecutionId(), 1);

                filteredInstrumentFileImportService.insertObjects(includedEntries, list.getReference());
                log.info("Saving filtered instrument data in db for list id {} version {}", listRaw.getId(), versionRaw.getId());
                incrementProgress(batchExport.getBatchExecutionId(), percent);

                // TODO: last constructor argument was cut off in the screenshot ("list.ge...") - check in IDE
                sixFilteredPollerRepository.save(new SixFilteredPoller("INSTR", LocalDateTime.now(), listRaw.getId(), versionRaw.getId(), list.getReference()));
                log.info("Six filtered poller for instrument data list ref {} version {}", list.getReference(), versionRaw.getId());
                incrementProgress(batchExport.getBatchExecutionId(), 1);

            } else if (listRaw.getImportFileType().isSIXStructuredFileType()) {
                List<StructuredFile> includedEntries = sixFileFilterService.filterStructured(sixFilters, listRaw.getId(), versionRaw.getId(), cmicSixFilters, e014071SixFilters,
                        batchExport.getBatchExecutionId(), percent / 1.5);
                log.info("Filtered Structured size : {}, list ref : {}", includedEntries.size(), list.getReference());

                filteredStructureFileRepository.deleteStrucutBatch(versionRaw.getId(), list.getReference(), dbBatchSize);
                log.info("Deleted filtered structured data in db of current version {}, if exists", versionRaw.getId());
                incrementProgress(batchExport.getBatchExecutionId(), 1);

                filteredStructuredFileImportService.insertObjects(includedEntries, list.getReference());
                log.info("Saving filtered structured data in db for list id {} version {}", listRaw.getId(), versionRaw.getId());
                incrementProgress(batchExport.getBatchExecutionId(), percent);

                // TODO: last constructor argument was cut off in the screenshot - check in IDE
                sixFilteredPollerRepository.save(new SixFilteredPoller("STRUCT", LocalDateTime.now(), listRaw.getId(), versionRaw.getId(), list.getReference()));
                log.info("Six filtered poller for structured data list ref {} version {}", list.getReference(), versionRaw.getId());
                incrementProgress(batchExport.getBatchExecutionId(), 1);

            } else if (listRaw.getImportFileType().isSIXOptionsFileType()) {
                List<OptionsFile> includedEntries = sixFileFilterService.filterOptions(sixFilters, listRaw.getId(), versionRaw.getId(), cmicSixFilters, e014071SixFilters,
                        batchExport.getBatchExecutionId(), percent / 1.5);
                log.info("Filtered Option size : {}, list ref : {}", includedEntries.size(), list.getReference());

                filteredOptionsFileRepository.deleteOptionsBatch(versionRaw.getId(), list.getReference(), dbBatchSize);
                log.info("Deleted options instrument data in db of current version {}, if exists", versionRaw.getId());
                incrementProgress(batchExport.getBatchExecutionId(), 1);

                filteredOptionsFileImportService.insertObjects(includedEntries, list.getReference());
                log.info("Saving filtered options data in db for list id {} version {}", listRaw.getId(), versionRaw.getId());
                incrementProgress(batchExport.getBatchExecutionId(), percent);

                // TODO: last constructor argument was cut off in the screenshot - check in IDE
                sixFilteredPollerRepository.save(new SixFilteredPoller("OPTIONS", LocalDateTime.now(), listRaw.getId(), versionRaw.getId(), list.getReference()));
                log.info("Six filtered poller for options data list ref {} version {}", list.getReference(), versionRaw.getId());
                incrementProgress(batchExport.getBatchExecutionId(), 1);
            }
        }
        log.debug("Filter six list files COMPLETED");
    }

    public void incrementProgress(Long batchJobExecutionId, double percent) {
        int increasedPercent = (int) (percent / 3);
        batchProgressService.incrementExportBatchForSix(batchJobExecutionId, increasedPercent);
    }
}
