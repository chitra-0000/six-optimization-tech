package com.bnpp.regliss.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

// TODO: re-add the project imports in the IDE (Alt+Enter / Optimize Imports) for:
// ReglissBatchProfile, CloseResourcesAfter, ReglissList, ReglissException, ImportFileType, ListType, ListTypeBuilder,
// FilteredFileBundle, FilteredInstrumentFile, FilteredStructuredFile, FilteredSixTarget, ReglissListRepository,
// FilteredInstrumentFileRepository, FilteredStructureFileRepository, SixXmlGenerationService, BatchProgressService,
// BatchManagementService, BatchJobExecution, BatchJobExecutionRepository, EmailService, SixFilteredStore, SixFilteredPoller,
// SixExportProgress,
// SixExportException, SixExportRunGuard

/**
 * Export step 2: when every file type of allow.six.file.integration was filtered for an output list, builds its
 * CONVERTER-...xml file (generic rules + ListTypeBuilder + SixXmlGenerationService, all unchanged).
 *
 * Part 3 changes:
 *  - a list's file is built as soon as every file type of that list is ready (951 can be in DJ IN while 952 is
 *    still being filtered);
 *  - exactly once on two servers: the READY rows of a list are claimed (FOR UPDATE SKIP LOCKED) and get STATUS
 *    BUILDING in the same transaction; DONE when the file is in DJ IN (SixFilteredStore);
 *  - an error while building ONE file: logged with all technical details, short alert mail, the list is DROPPED and
 *    its jobs stay below 100 % (KO); the server continues with the next ready list;
 *  - a list that failed while filtering / writing on a server is never built: its ready rows are DROPPED;
 *  - progress: 20 % of each list's share per file, at most 95 % automatically; 100 % + end date only by the existing
 *    service, when EVERY list of the job has its file (before: the first file set 100 %, even with status KO);
 *  - waiting jobs are kept alive only while the job they wait for is alive;
 *  - filtered rows read with their targets in ONE query per table (no N+1 on the EAGER targets);
 *  - duplicate-by-sanction rule with a host-ISIN index (O(n) instead of O(n x m)); SAME result (see comment there);
 *  - the FILTERED_SIX_* rows are no longer deleted here: the nightly cleanup empties these tables.
 */
@ReglissBatchProfile
@Component
@Slf4j
public class SixXmlGenerationPoller {

    /** The UI shows a job without end date as KO when it was not updated for 30 minutes. */
    private static final long KO_AFTER_MINUTES = 30;

    DateTimeFormatter inputFormatter = DateTimeFormatter.ofPattern("yyyyMMdd");
    DateTimeFormatter outputFormatter = DateTimeFormatter.ISO_LOCAL_DATE;

    @Autowired
    private SixFilteredStore sixFilteredStore;
    @Autowired
    private ReglissListRepository reglissListRepository;
    @Autowired
    private ListTypeBuilder listTypeBuilder;
    @Autowired
    private SixXmlGenerationService sixXmlGenerationService;
    @Autowired
    private FilteredInstrumentFileRepository filteredInstrumentFileRepository;
    @Autowired
    private FilteredStructureFileRepository filteredStructureFileRepository;
    @Autowired
    private BatchProgressService batchProgressService;
    @Autowired
    private BatchManagementService batchManagementService;
    @Autowired
    private EmailService emailService;
    @Autowired
    private BatchJobExecutionRepository batchJobExecutionRepository;
    @Autowired
    private SixExportRunGuard sixExportRunGuard;

    @Value("${allow.six.file.integration}")
    private String allowSixFilesToIntegrate;

    @Scheduled(cron = "${task.batch.export.generation}")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @CloseResourcesAfter
    public void generateXmlFiles() {

        List<ReglissList> childLists = reglissListRepository.findByDJFormatNotDeleted(ImportFileType.SIX_MAIN_FILE);
        Set<String> required = Arrays.stream(allowSixFilesToIntegrate.toUpperCase().split(","))
                .map(String::trim).filter(t -> !t.isEmpty()).collect(Collectors.toSet());

        for (ReglissList childList : childLists) {
            try {
                generateXmlFile(childList, required);
            } catch (RuntimeException e) {
                // an error OUTSIDE the stages (reading SIX_FILTERED_POLLER ...): logged, next list, next minute
                log.error("SIX XML step: list {} not processed in this run: {}", childList.getReference(),
                        SixExportException.rootCause(e), e);
            }
        }
    }

    private void generateXmlFile(ReglissList childList, Set<String> required) {
        String reference = childList.getReference();
        dropRowsOfFailedList(reference, required);

        SixFilteredStore.PollerClaim claim = sixFilteredStore.claimPollerRows(reference, required);
        if (!claim.isClaimed()) {
            keepWaitingJobsAlive(reference, claim.getRows());
            return;
        }
        List<Long> rowIds = claim.getRows().stream().map(SixFilteredStore.PollerRow::getId).collect(Collectors.toList());
        Set<Long> batchJobExecutionIds = claim.jobIds();
        String where = "CONVERTER file of list " + reference + " (" + claim + ", delivery "
                + claim.getRows().stream().map(r -> sixExportRunGuard.deliveryOf(r.getRawVersionId())).distinct()
                .collect(Collectors.joining(", ")) + ", jobs " + batchJobExecutionIds + ")";

        // the list may have failed on a server between the drop above and the claim (a complete list is still
        // built when only the delivery was stopped)
        Optional<SixFilteredPoller> stop = stopOf(reference, claim.getRows())
                .filter(f -> !SixExportRunGuard.FAILED_ALL.equals(f.getStatus()));
        if (stop.isPresent()) {
            sixFilteredStore.markBuildFailed(rowIds);
            log.error("SIX XML step: {} not generated because {}", where, sixExportRunGuard.describe(stop.get()));
            return;
        }

        log.info("Data filtering completed: generating the {}", where);
        long start = System.currentTimeMillis();
        try {
            List<FilteredInstrumentFile> i = stage(SixExportException.Stage.READ_FILTERED, where, reference, batchJobExecutionIds,
                    () -> new ArrayList<>(filteredInstrumentFileRepository.findLatestByListRefWithTargets(reference)));
            keepAlive(batchJobExecutionIds);
            List<FilteredStructuredFile> s = stage(SixExportException.Stage.READ_FILTERED, where, reference, batchJobExecutionIds,
                    () -> new ArrayList<>(filteredStructureFileRepository.findLatestByListRefWithTargets(reference)));
            log.info("Fetched {} filtered instrument rows and {} filtered structure rows for list ref {}", i.size(), s.size(), reference);
            keepAlive(batchJobExecutionIds);

            FilteredFileBundle filteredFileBundle = stage(SixExportException.Stage.GENERIC_RULES, where, reference, batchJobExecutionIds,
                    () -> applyGenericFilters(i, s, new FilteredFileBundle(), reference));
            keepAlive(batchJobExecutionIds);

            ListType listType = stage(SixExportException.Stage.BUILD_XML, where, reference, batchJobExecutionIds, () -> {
                Map<ReglissList, FilteredFileBundle> bundle = new HashMap<>();
                bundle.put(childList, filteredFileBundle);
                return listTypeBuilder.generationOfListTypes(bundle, reference);
            });
            String fileName = stage(SixExportException.Stage.WRITE_FILE, where, reference, batchJobExecutionIds,
                    () -> sixXmlGenerationService.createAndUploadFileOrThrow(listType, batchJobExecutionIds));
            sixFilteredStore.markBuilt(rowIds);
            log.info("{} generated as {} in {} ms", where, fileName, System.currentTimeMillis() - start);
        } catch (SixExportException e) {
            // this list only: its jobs stay below 100 % (KO), the other lists continue
            log.error(e.getMessage(), e);
            quietly("mark the list as failed", () -> sixFilteredStore.markBuildFailed(rowIds));
            quietly("send the alert mail", () -> emailService.sendEmailDjImportGeneralError(childList, e.mailText()));
            return;
        }
        for (Long job : batchJobExecutionIds) {
            addXmlProgressAndFinish(job);
        }
    }

    /** Runs one stage of the XML step; any error becomes a SixExportException that says where it stopped. */
    private <T> T stage(SixExportException.Stage stage, String where, String reference, Set<Long> jobs, Supplier<T> work) {
        try {
            return work.get();
        } catch (RuntimeException e) {
            throw new SixExportException(stage, where, reference, null, jobs.isEmpty() ? null : jobs.iterator().next(), e);
        }
    }

    /**
     * Ready rows that will never become a file are DROPPED:
     *  - the list failed (FAILED of this list, on either server);
     *  - the export of the delivery was stopped (FAILED_ALL) and the list is incomplete with nobody still filtering it
     *    (no PENDING row): the missing file type will never come. A COMPLETE list is still built.
     */
    private void dropRowsOfFailedList(String reference, Set<String> required) {
        List<SixFilteredStore.PollerRow> rows = sixFilteredStore.readyRows(reference);
        if (rows.isEmpty()) {
            return;
        }
        boolean complete = rows.stream().map(SixFilteredStore.PollerRow::getFileType).collect(Collectors.toSet()).containsAll(required);
        List<Long> dropped = new ArrayList<>();
        String reason = null;
        for (SixFilteredStore.PollerRow row : rows) {
            Optional<SixFilteredPoller> stop = stopOf(reference, Collections.singletonList(row));
            boolean deliveryStopped = stop.isPresent() && SixExportRunGuard.FAILED_ALL.equals(stop.get().getStatus());
            if (stop.isPresent() && (!deliveryStopped || (!complete && sixFilteredStore.jobsStillFiltering(reference).isEmpty()))) {
                dropped.add(row.getId());
                reason = sixExportRunGuard.describe(stop.get());
            }
        }
        if (!dropped.isEmpty()) {
            sixFilteredStore.dropReady(dropped);
            log.error("SIX XML step: CONVERTER file of list {} not generated (rows dropped) because {}", reference, reason);
        }
    }

    private Optional<SixFilteredPoller> stopOf(String reference, List<SixFilteredStore.PollerRow> rows) {
        for (SixFilteredStore.PollerRow row : rows) {
            if (row.getRawVersionId() != null) {
                Optional<SixFilteredPoller> stop = sixExportRunGuard.listStop(row.getRawVersionId(), row.getBatchJobExecutionId(), reference);
                if (stop.isPresent()) {
                    return stop;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * A list written for one file type waits for the other one. Its job is kept alive only while a job that still has
     * to filter this list is itself alive (updated in the last 30 minutes): if that server crashed, nobody refreshes
     * the waiting job either, and both become KO as expected.
     */
    private void keepWaitingJobsAlive(String reference, List<SixFilteredStore.PollerRow> waitingRows) {
        if (waitingRows.isEmpty()) {
            return;
        }
        LocalDateTime aliveSince = LocalDateTime.now().minusMinutes(KO_AFTER_MINUTES);
        boolean otherJobAlive = sixFilteredStore.jobsStillFiltering(reference).stream()
                .map(batchJobExecutionRepository::findById)
                .anyMatch(job -> job.map(BatchJobExecution::getLastUpdateDate).filter(d -> d.isAfter(aliveSince)).isPresent());
        if (otherJobAlive) {
            keepAlive(waitingRows.stream().map(SixFilteredStore.PollerRow::getBatchJobExecutionId).filter(Objects::nonNull)
                    .collect(Collectors.toSet()));
        }
    }

    /**
     * XML share of one list = 20 % of the list share (90 % / number of lists of the job). The automatic total never
     * passes 95 %; when EVERY list of the job has its file, the existing service sets 100 % and the end date.
     */
    private void addXmlProgressAndFinish(Long job) {
        try {
            SixFilteredStore.JobLists lists = sixFilteredStore.jobLists(job);
            double current = batchJobExecutionRepository.findById(job).map(j -> percentOf(j.getPercent())).orElse(0.0);
            double target = Math.min(SixExportProgress.AUTOMATIC_MAX,
                    current + SixExportProgress.listShare(lists.getTotal()) * SixExportProgress.XML_PART);
            int increment = (int) Math.floor(target) - (int) Math.floor(current);
            batchProgressService.incrementExportBatchForSix(job, Math.max(0, increment));
            if (lists.allBuilt()) {
                batchManagementService.markBatchExecutionFinishedBySys(job);
                batchManagementService.updateBatchExecutionCompletedProgress(job);
                log.info("SIX export job {}: all {} lists have their file - 100 %", job, lists.getTotal());
            } else {
                log.info("SIX export job {}: {} of {} lists have their file, {} open, {} failed", job, lists.getDone(),
                        lists.getTotal(), lists.getOpen(), lists.getDropped());
            }
        } catch (RuntimeException e) {
            log.error("SIX XML step: could not update job {}: {}", job, SixExportException.rootCause(e), e);
        }
    }

    private static double percentOf(Object percent) {
        return percent instanceof Number ? ((Number) percent).doubleValue() : 0.0;
    }

    private static void quietly(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.error("SIX XML step: could not {}: {}", what, SixExportException.rootCause(e), e);
        }
    }

    private void keepAlive(Set<Long> batchJobExecutionIds) {
        for (Long batchJobExecutionId : batchJobExecutionIds) {
            try {
                batchProgressService.incrementExportBatchForSix(batchJobExecutionId, 0);
            } catch (RuntimeException e) {
                log.warn("Could not refresh job {}: {}", batchJobExecutionId, e.getMessage());
            }
        }
    }

    public FilteredFileBundle applyGenericFilters(List<FilteredInstrumentFile> filteredInstru, List<FilteredStructuredFile> filteredStructured,
                                                  FilteredFileBundle filteredFileBundle, String listReference) {
        // remove duplicated ISIN inside files
        filteredInstru = removeDuplicatedISINFromInstrumentFile(filteredInstru, listReference);
        filteredStructured = removeDuplicatedISINFromStructuredFile(filteredStructured, listReference);

        // remove duplicated ISIN between files
        Set<String> instrumentsIsins = filteredInstru.stream()
                .filter(i -> i.getIsin() != null).map(FilteredInstrumentFile :: getIsin).collect(Collectors.toSet());
        filteredStructured.removeIf(sf -> sf.getHostIsin()!= null && instrumentsIsins.contains(sf.getHostIsin()));

        // remove duplicated ISIN between files based on sanctions
        removeDuplicatedIsinBasedOnSanctioned(filteredInstru, filteredStructured);

        filteredFileBundle.addInstrumentFiles(filteredInstru);
        filteredFileBundle.addStructuredFiles(filteredStructured);
        return filteredFileBundle;
    }

    /**
     * Duplicate-by-sanction rule - business rule UNCHANGED, only the search is indexed (it scanned the whole structured
     * list for every instrument: O(n x m)). For every instrument ISIN, in the same order as before:
     *  - its targets' SANCTIONED values contain YES: the structured products whose HOST_ISIN is that ISIN are removed;
     *  - otherwise, among the structured products whose HOST_ISIN equals the instrument's joined SANCTIONED text
     *    (getValue(), compared exactly as before): those whose targets contain NO are removed; if one whose targets
     *    contain YES is still there, the instrument is removed.
     * Removals are applied at the end; the "still there" checks see the removals made so far, exactly like the
     * removeIf / removeAll calls on the live lists did.
     */
    private void removeDuplicatedIsinBasedOnSanctioned(List<FilteredInstrumentFile> filteredInstru,
                                                       List<FilteredStructuredFile> filteredStructured) {
        Map<String, String> instrumentISINWithSanctionedDetail = filteredInstru.stream()
                .filter(i -> i.getIsin() != null)
                .collect(Collectors.toMap(
                        FilteredInstrumentFile::getIsin,
                        i -> i.getSixTargets().stream().map(FilteredSixTarget::getSanctioned).distinct()
                                .collect(Collectors.joining(" - "))));

        Map<String, List<FilteredStructuredFile>> structuredByHostIsin = new HashMap<>();
        for (FilteredStructuredFile sf : filteredStructured) {
            structuredByHostIsin.computeIfAbsent(sf.getHostIsin(), k -> new ArrayList<>()).add(sf);
        }
        Map<FilteredStructuredFile, String> sanctionedText = new IdentityHashMap<>();
        Function<FilteredStructuredFile, String> sanctionedOf = sf -> sanctionedText.computeIfAbsent(sf,
                k -> k.getSixTargets().stream().map(FilteredSixTarget::getSanctioned).collect(Collectors.joining(" - ")).toUpperCase());
        Set<FilteredStructuredFile> removedStructured = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> removedInstrumentIsins = new HashSet<>();

        for (Map.Entry<String, String> instrumentISIN : instrumentISINWithSanctionedDetail.entrySet()) {
            if (instrumentISIN.getValue().toUpperCase().contains("YES")) {
                removedStructured.addAll(structuredByHostIsin.getOrDefault(instrumentISIN.getKey(), Collections.emptyList()));
            } else {
                List<FilteredStructuredFile> sameHost = structuredByHostIsin.getOrDefault(instrumentISIN.getValue(), Collections.emptyList());
                for (FilteredStructuredFile sf : sameHost) {
                    if (!removedStructured.contains(sf) && sanctionedOf.apply(sf).contains("NO")) {
                        removedStructured.add(sf);
                    }
                }
                boolean sanctionedStructLeft = sameHost.stream()
                        .anyMatch(sf -> !removedStructured.contains(sf) && sanctionedOf.apply(sf).contains("YES"));
                if (sanctionedStructLeft) {
                    removedInstrumentIsins.add(instrumentISIN.getKey());
                }
            }
        }
        if (!removedStructured.isEmpty()) {
            filteredStructured.removeIf(removedStructured::contains);
        }
        if (!removedInstrumentIsins.isEmpty()) {
            filteredInstru.removeIf(i -> removedInstrumentIsins.contains(i.getIsin()));
        }
    }

    private List<FilteredStructuredFile> removeDuplicatedISINFromStructuredFile(List<FilteredStructuredFile> filteredStructured,
                                                                                String listReference) {

        if (filteredStructured == null || filteredStructured.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, List<FilteredStructuredFile>> grouped = filteredStructured.stream()
                .filter(Objects::nonNull)
                .filter(f -> f.getHostIsin() != null)
                .collect(Collectors.groupingBy(FilteredStructuredFile :: getHostIsin));

        List<FilteredStructuredFile> filteredStructuredFiles = grouped.values().stream()
                .map(this::reduceStructuredGroup)
                .collect(Collectors.toList());

        Map<String, FilteredStructuredFile> groupByExternalReference = filteredStructuredFiles.stream()
                .collect(Collectors.toMap(
                        FilteredStructuredFile::getHostCh,
                        file -> file,
                        (existing, replacement) -> {
                            throw new IllegalStateException(String.format("Duplicate external reference found while applying " +
                                    "generic filters in Structure - %s for list ref - %s", existing.getHostCh(), listReference));
                        }
                ));

        log.info("Strucuture reference size while applying generic filters : {}", groupByExternalReference.size());
        return filteredStructuredFiles;

    }

    private List<FilteredInstrumentFile> removeDuplicatedISINFromInstrumentFile(List<FilteredInstrumentFile> filteredInstru,
                                                                                String listReference) {
        if (filteredInstru == null || filteredInstru.isEmpty()) {
            return Collections.emptyList();
        } else {
            Map<String, List<FilteredInstrumentFile>> grouped = filteredInstru.stream()
                    .filter(Objects::nonNull)
                    .filter(f -> f.getIsin() != null)
                    .collect(Collectors.groupingBy(FilteredInstrumentFile :: getIsin));

            List<FilteredInstrumentFile> filteredInstrumentFiles = grouped.values().stream()
                    .map(this::reduceInstructionGroup)
                    .collect(Collectors.toList());

            Map<String, FilteredInstrumentFile> groupByExternalReference = filteredInstrumentFiles.stream()
                    .collect(Collectors.toMap(
                            FilteredInstrumentFile::getChValor,
                            file -> file,
                            (existing, replacement) -> {
                                throw new IllegalStateException(String.format("Duplicate external reference found while applying " +
                                        "generic filters in Instruments - %s for list ref - %s", existing.getChValor(), listReference));
                            }
                    ));

            log.info("Instruments reference size while applying generic filters : {}", groupByExternalReference.size());
            return filteredInstrumentFiles;
        }
    }

    private String formatDate(String dateValue) {
        LocalDate formattedDate = LocalDate.parse(dateValue, inputFormatter);
        return formattedDate.format(outputFormatter);
    }

    private FilteredInstrumentFile reduceInstructionGroup(List<FilteredInstrumentFile> filteredInstrumentFiles) {
        if (filteredInstrumentFiles.size() > 1) {
            FilteredInstrumentFile merged = filteredInstrumentFiles.stream().findFirst().orElseThrow(()->
                    new ReglissException("Unable to merge the content of duplicated ISIN"));
            merged.setLinkEntity(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getLinkEntity).filter(Objects::nonNull)
                    .distinct().collect(Collectors.joining(" - ")));
            merged.setLinkCsid(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getLinkCsid).filter(Objects::nonNull)
                    .distinct().collect(Collectors.joining(" - ")));
            merged.setNameDirectIssuer(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getNameDirectIssuer)
                    .filter(Objects::nonNull).distinct().collect(Collectors.joining(" - ")));
            merged.setSanctionedParentEntity(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getSanctionedParentEntity)
                    .filter(Objects::nonNull).distinct().collect(Collectors.joining(" - ")));
            merged.setInstrName(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getInstrName).filter(Objects::nonNull)
                    .distinct().collect(Collectors.joining(" - ")));
            merged.setFisn(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getFisn).filter(Objects::nonNull).distinct()
                    .collect(Collectors.joining(" - ")));
            merged.setIndicativeIssueDate(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getIndicativeIssueDate)
                    .filter(Objects::nonNull).map(this ::formatDate).distinct().collect(Collectors.joining(" - ")));
            merged.setInstrumentType(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getInstrumentType)
                    .filter(Objects::nonNull).distinct().collect(Collectors.joining(" - ")));
            merged.setSanctionsRelevantAssetClass(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getSanctionsRelevantAssetClass)
                    .filter(Objects::nonNull).distinct().collect(Collectors.joining(" - ")));
            merged.setMainInstrument(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getMainInstrument)
                    .filter(Objects::nonNull).distinct().collect(Collectors.joining(" - ")));
            merged.setEquityTypeOfIssuance(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getEquityTypeOfIssuance)
                    .filter(Objects::nonNull).distinct().collect(Collectors.joining(" - ")));
            merged.setDenominationCurrency(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getDenominationCurrency)
                    .filter(Objects::nonNull).distinct().collect(Collectors.joining(" - ")));
            merged.setMaturityDate(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getMaturityDate).filter(Objects::nonNull)
                    .map(this ::formatDate).distinct().collect(Collectors.joining(" - ")));
            merged.setDebtLifetimeInDays(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getDebtLifetimeInDays)
                    .filter(Objects::nonNull).distinct().collect(Collectors.joining(" - ")));
            merged.setIssueDate(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getIssueDate).filter(Objects::nonNull)
                    .map(this ::formatDate).distinct().collect(Collectors.joining(" - ")));
            merged.setCapitalChangeDate(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getCapitalChangeDate)
                    .filter(Objects::nonNull).map(this ::formatDate).distinct().collect(Collectors.joining(" - ")));
            merged.setSedol(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getSedol).filter(Objects::nonNull).distinct()
                    .collect(Collectors.joining(" - ")));
            merged.setCusip(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getCusip).filter(Objects::nonNull).distinct()
                    .collect(Collectors.joining(" - ")));
            merged.setCins(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getCins).filter(Objects::nonNull).distinct()
                    .collect(Collectors.joining(" - ")));
            merged.setAustrian(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getAustrian).filter(Objects::nonNull).distinct()
                    .collect(Collectors.joining(" - ")));
            merged.setBelgian(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getBelgian).filter(Objects::nonNull).distinct()
                    .collect(Collectors.joining(" - ")));
            merged.setCanadian(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getCanadian).filter(Objects::nonNull).distinct()
                    .collect(Collectors.joining(" - ")));
            merged.setGerman(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getGerman).filter(Objects::nonNull).distinct()
                    .collect(Collectors.joining(" - ")));
            merged.setDenmark(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getDenmark).filter(Objects::nonNull).distinct()
                    .collect(Collectors.joining(" - ")));
            merged.setFranceRga(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getFranceRga).filter(Objects::nonNull).distinct()
                    .collect(Collectors.joining(" - ")));
            merged.setFranceEuroclear(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getFranceEuroclear)
                    .filter(Objects::nonNull).distinct().collect(Collectors.joining(" - ")));
            merged.setItalian(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getItalian).filter(Objects::nonNull).distinct()
                    .collect(Collectors.joining(" - ")));
            merged.setJapaneseCurrent(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getJapaneseCurrent).filter(Objects::nonNull)
                    .distinct().collect(Collectors.joining(" - ")));
            merged.setJapaneseNew(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getJapaneseNew).filter(Objects::nonNull)
                    .distinct().collect(Collectors.joining(" - ")));
            merged.setLuxembourg(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getLuxembourg).filter(Objects::nonNull)
                    .distinct().collect(Collectors.joining(" - ")));
            merged.setNetherland(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getNetherland).filter(Objects::nonNull)
                    .distinct().collect(Collectors.joining(" - ")));
            merged.setNorwegian(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getNorwegian).filter(Objects::nonNull)
                    .distinct().collect(Collectors.joining(" - ")));
            merged.setSwedish(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getSwedish).filter(Objects::nonNull).distinct()
                    .collect(Collectors.joining(" - ")));
            merged.setXsIntNumber(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getXsIntNumber).filter(Objects::nonNull)
                    .distinct().collect(Collectors.joining(" - ")));
            merged.setPortugal(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getPortugal).filter(Objects::nonNull).distinct()
                    .collect(Collectors.joining(" - ")));
            merged.setSouthKorea(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getSouthKorea).filter(Objects::nonNull)
                    .distinct().collect(Collectors.joining(" - ")));
            merged.setHongKong(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getHongKong).filter(Objects::nonNull).distinct()
                    .collect(Collectors.joining(" - ")));
            merged.setFigiGlobalId(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getFigiGlobalId).filter(Objects::nonNull)
                    .distinct().collect(Collectors.joining(" - ")));

            List<FilteredSixTarget> filteredSixTargetList = new ArrayList<>();

            for (FilteredInstrumentFile filteredInstrumentFile : filteredInstrumentFiles) {
                for (FilteredSixTarget filteredSixTarget : filteredInstrumentFile.getSixTargets()) {

                    FilteredSixTarget mergeFilteredSixTarget = new FilteredSixTarget();
                    mergeFilteredSixTarget.setReasonForChange(filteredSixTarget.getReasonForChange());
                    mergeFilteredSixTarget.setTarget(filteredSixTarget.getTarget());
                    mergeFilteredSixTarget.setSanctioned(filteredSixTarget.getSanctioned());
                    mergeFilteredSixTarget.setSanctionsRationale(filteredSixTarget.getSanctionsRationale());
                    mergeFilteredSixTarget.setRegime(filteredSixTarget.getRegime());
                    mergeFilteredSixTarget.setLegalBasis(filteredSixTarget.getLegalBasis());
                    filteredSixTargetList.add(mergeFilteredSixTarget);
                }
            }
            merged.setSixTargets(filteredSixTargetList);
            return merged;

        } else {
            FilteredInstrumentFile merged = filteredInstrumentFiles.stream().findFirst().orElseThrow(()->
                    new ReglissException("No instrument file found to merge duplicated ISIN"));
            merged.setIndicativeIssueDate(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getIndicativeIssueDate)
                    .filter(Objects::nonNull).map(this ::formatDate).distinct().collect(Collectors.joining(" - ")));
            merged.setMaturityDate(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getMaturityDate).filter(Objects::nonNull)
                    .map(this ::formatDate).distinct().collect(Collectors.joining(" - ")));
            merged.setIssueDate(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getIssueDate).filter(Objects::nonNull)
                    .map(this ::formatDate).distinct().collect(Collectors.joining(" - ")));
            merged.setCapitalChangeDate(filteredInstrumentFiles.stream().map(FilteredInstrumentFile::getCapitalChangeDate)
                    .filter(Objects::nonNull).map(this ::formatDate).distinct().collect(Collectors.joining(" - ")));
            return merged;
        }
    }
    private FilteredStructuredFile reduceStructuredGroup(List<FilteredStructuredFile> filteredStructuredFiles) {
        if (filteredStructuredFiles.size() > 1) {
            FilteredStructuredFile merged = filteredStructuredFiles.stream().findFirst().orElseThrow(()->
                    new ReglissException("Unable to merge the content of duplicated ISIN"));
            merged.setHostCh(filteredStructuredFiles.stream().map(FilteredStructuredFile::getHostCh).filter(Objects::nonNull).distinct()
                    .collect(Collectors.joining(" - ")));
            merged.setHostGk(filteredStructuredFiles.stream().map(FilteredStructuredFile::getHostGk).filter(Objects::nonNull).distinct()
                    .collect(Collectors.joining(" - ")));
            merged.setHostIssuerShortname(filteredStructuredFiles.stream().map(FilteredStructuredFile::getHostIssuerShortname)
                    .filter(Objects::nonNull).distinct().collect(Collectors.joining(" - ")));
            merged.setDescription(filteredStructuredFiles.stream().map(FilteredStructuredFile::getDescription).filter(Objects::nonNull)
                    .distinct().collect(Collectors.joining(" - ")));
            merged.setFisn(filteredStructuredFiles.stream().map(FilteredStructuredFile::getFisn).filter(Objects::nonNull).distinct()
                    .collect(Collectors.joining(" - ")));
            merged.setIndicativeIssueDate(filteredStructuredFiles.stream().map(FilteredStructuredFile::getIndicativeIssueDate)
                    .filter(Objects::nonNull).map(this ::formatDate).distinct().collect(Collectors.joining(" - ")));
            merged.setInstrumentType(filteredStructuredFiles.stream().map(FilteredStructuredFile::getInstrumentType).filter(Objects::nonNull)
                    .distinct().collect(Collectors.joining(" - ")));
            merged.setUnderlyingCh(filteredStructuredFiles.stream().map(FilteredStructuredFile::getUnderlyingCh).filter(Objects::nonNull)
                    .distinct().collect(Collectors.joining(" - ")));
            merged.setUnderlyingIsin(filteredStructuredFiles.stream().map(FilteredStructuredFile::getUnderlyingIsin).filter(Objects::nonNull)
                    .distinct().collect(Collectors.joining(" - ")));
            merged.setDenominationCurrency(filteredStructuredFiles.stream().map(FilteredStructuredFile::getDenominationCurrency)
                    .filter(Objects::nonNull).distinct().collect(Collectors.joining(" - ")));
            merged.setMaturityDate(filteredStructuredFiles.stream().map(FilteredStructuredFile::getMaturityDate).filter(Objects::nonNull)
                    .map(this ::formatDate).distinct().collect(Collectors.joining(" - ")));
            merged.setUnderlyingGk(filteredStructuredFiles.stream().map(FilteredStructuredFile::getUnderlyingGk).filter(Objects::nonNull)
                    .distinct().collect(Collectors.joining(" - ")));
            merged.setIssueDate(filteredStructuredFiles.stream().map(FilteredStructuredFile::getIssueDate).filter(Objects::nonNull)
                    .map(this ::formatDate).distinct().collect(Collectors.joining(" - ")));
            merged.setUnderlyingIssuerShortname(filteredStructuredFiles.stream().map(FilteredStructuredFile::getUnderlyingIssuerShortname)
                    .filter(Objects::nonNull).distinct().collect(Collectors.joining(" - ")));
            merged.setSedol(filteredStructuredFiles.stream().map(FilteredStructuredFile::getSedol).filter(Objects::nonNull).distinct()
                    .collect(Collectors.joining(" - ")));
            merged.setCusip(filteredStructuredFiles.stream().map(FilteredStructuredFile::getCusip).filter(Objects::nonNull).distinct()
                    .collect(Collectors.joining(" - ")));
            merged.setCins(filteredStructuredFiles.stream().map(FilteredStructuredFile::getCins).filter(Objects::nonNull).distinct()
                    .collect(Collectors.joining(" - ")));
            merged.setAustrian(filteredStructuredFiles.stream().map(FilteredStructuredFile::getAustrian).filter(Objects::nonNull).distinct()
                    .collect(Collectors.joining(" - ")));
            merged.setBelgian(filteredStructuredFiles.stream().map(FilteredStructuredFile::getBelgian).filter(Objects::nonNull).distinct()
                    .collect(Collectors.joining(" - ")));
            merged.setCanadian(filteredStructuredFiles.stream().map(FilteredStructuredFile::getCanadian).filter(Objects::nonNull).distinct()
                    .collect(Collectors.joining(" - ")));
            merged.setGerman(filteredStructuredFiles.stream().map(FilteredStructuredFile::getGerman).filter(Objects::nonNull).distinct()
                    .collect(Collectors.joining(" - ")));
            merged.setDenmark(filteredStructuredFiles.stream().map(FilteredStructuredFile::getDenmark).filter(Objects::nonNull).distinct()
                    .collect(Collectors.joining(" - ")));
            merged.setFranceRga(filteredStructuredFiles.stream().map(FilteredStructuredFile::getFranceRga).filter(Objects::nonNull).distinct()
                    .collect(Collectors.joining(" - ")));
            merged.setFranceEuroClear(filteredStructuredFiles.stream().map(FilteredStructuredFile::getFranceEuroClear)
                    .filter(Objects::nonNull).distinct().collect(Collectors.joining(" - ")));
            merged.setItalian(filteredStructuredFiles.stream().map(FilteredStructuredFile::getItalian).filter(Objects::nonNull).distinct()
                    .collect(Collectors.joining(" - ")));
            merged.setJapaneseCurrent(filteredStructuredFiles.stream().map(FilteredStructuredFile::getJapaneseCurrent)
                    .filter(Objects::nonNull).distinct().collect(Collectors.joining(" - ")));
            merged.setJapaneseNew(filteredStructuredFiles.stream().map(FilteredStructuredFile::getJapaneseNew).filter(Objects::nonNull)
                    .distinct().collect(Collectors.joining(" - ")));
            merged.setLuxembourg(filteredStructuredFiles.stream().map(FilteredStructuredFile::getLuxembourg)
                    .filter(Objects::nonNull).distinct().collect(Collectors.joining(" - ")));
            merged.setNetherland(filteredStructuredFiles.stream().map(FilteredStructuredFile::getNetherland)
                    .filter(Objects::nonNull).distinct().collect(Collectors.joining(" - ")));
            merged.setNorwegian(filteredStructuredFiles.stream().map(FilteredStructuredFile::getNorwegian)
                    .filter(Objects::nonNull).distinct().collect(Collectors.joining(" - ")));
            merged.setSwedish(filteredStructuredFiles.stream().map(FilteredStructuredFile::getSwedish)
                    .filter(Objects::nonNull).distinct().collect(Collectors.joining(" - ")));
            merged.setXsIntNumber(filteredStructuredFiles.stream().map(FilteredStructuredFile::getXsIntNumber)
                    .filter(Objects::nonNull).distinct().collect(Collectors.joining(" - ")));
            merged.setPortugal(filteredStructuredFiles.stream().map(FilteredStructuredFile::getPortugal)
                    .filter(Objects::nonNull).distinct().collect(Collectors.joining(" - ")));
            merged.setSouthKorea(filteredStructuredFiles.stream().map(FilteredStructuredFile::getSouthKorea)
                    .filter(Objects::nonNull).distinct().collect(Collectors.joining(" - ")));
            merged.setHongKong(filteredStructuredFiles.stream().map(FilteredStructuredFile::getHongKong)
                    .filter(Objects::nonNull).distinct().collect(Collectors.joining(" - ")));
            merged.setFigiGlobalId(filteredStructuredFiles.stream().map(FilteredStructuredFile::getFigiGlobalId)
                    .filter(Objects::nonNull).distinct().collect(Collectors.joining(" - ")));

            List<FilteredSixTarget> filteredSixTargetList = new ArrayList<>();

            for (FilteredStructuredFile filteredStructuredFile : filteredStructuredFiles) {
                for (FilteredSixTarget filteredSixTarget : filteredStructuredFile.getSixTargets()) {

                    FilteredSixTarget mergeFilteredSixTarget = new FilteredSixTarget();
                    mergeFilteredSixTarget.setReasonForChange(filteredSixTarget.getReasonForChange());
                    mergeFilteredSixTarget.setTarget(filteredSixTarget.getTarget());
                    mergeFilteredSixTarget.setSanctioned(filteredSixTarget.getSanctioned());
                    mergeFilteredSixTarget.setSanctionsRationale(filteredSixTarget.getSanctionsRationale());
                    mergeFilteredSixTarget.setRegime(filteredSixTarget.getRegime());
                    mergeFilteredSixTarget.setLegalBasis(filteredSixTarget.getLegalBasis());
                    filteredSixTargetList.add(mergeFilteredSixTarget);
                }
            }
            merged.setSixTargets(filteredSixTargetList);
            return merged;

        } else {
            FilteredStructuredFile merged = filteredStructuredFiles.stream().findFirst().orElseThrow(()->
                    new ReglissException("No structure file found to merge duplicated ISIN"));
            merged.setIndicativeIssueDate(filteredStructuredFiles.stream().map(FilteredStructuredFile::getIndicativeIssueDate)
                    .filter(Objects::nonNull).map(this ::formatDate).distinct().collect(Collectors.joining(" - ")));
            merged.setMaturityDate(filteredStructuredFiles.stream().map(FilteredStructuredFile::getMaturityDate)
                    .filter(Objects::nonNull).map(this ::formatDate).distinct().collect(Collectors.joining(" - ")));
            merged.setIssueDate(filteredStructuredFiles.stream().map(FilteredStructuredFile::getIssueDate).filter(Objects::nonNull)
                    .map(this ::formatDate).distinct().collect(Collectors.joining(" - ")));
            return merged;
        }
    }
}
