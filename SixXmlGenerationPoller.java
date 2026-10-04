package com.bnpp.regliss.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.map.HashedMap;   // TODO: check in the IDE - could be org.apache.commons.collections.map.HashedMap
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@ReglissBatchProfile
@Component
@Slf4j
public class SixXmlGenerationPoller {

    DateTimeFormatter inputFormatter = DateTimeFormatter.ofPattern("yyyyMMdd");
    DateTimeFormatter outputFormatter = DateTimeFormatter.ISO_LOCAL_DATE;

    @Autowired
    private SixFilteredPollerRepository sixFilteredPollerRepo;
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
    private FilteredOptionsFileRepository filteredOptionsFileRepository;
    @Autowired
    private FilteredSixTargetRepository filteredSixTargetRepository;
    @Autowired
    private BatchJobExecutionRepository batchJobExecutionRepository;
    @Autowired
    private BatchProgressService batchProgressService;
    @Autowired
    private BatchManagementService batchManagementService;

    @Value("${six.db.batch}")
    private int dbBatchSize;
    @Value("${allow.six.file.integration}")
    private String allowSixFilesToIntegrate;

    @Scheduled(cron = "${task.batch.export.generation}")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @CloseResourcesAfter
    public void generateXmlFiles() {

        List<ReglissList> childLists = reglissListRepository.findByDJFormatNotDeleted(ImportFileType.SIX_MAIN_FILE);
        Set<String> required = Arrays.stream(allowSixFilesToIntegrate.toUpperCase().split(",")).collect(Collectors.toSet());

        for(ReglissList childList : childLists) {
            List<SixFilteredPoller> rows = sixFilteredPollerRepo.findBySixListReference(childList.getReference());
            boolean initiateGenericRules = rows.stream()
                    .map(SixFilteredPoller::getFileType)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet())
                    .containsAll(required);

            findBatchIdAndProgressActivity(rows);

            if (initiateGenericRules) {
                log.info("Data filtering completed for list ref {} of Instru and Struct", childList.getReference());
                Set<Long> batchJobExecutionIds = rows.stream().map(SixFilteredPoller::getBatchJobExecutionId).collect(Collectors.toSet());

                Map<ReglissList, FilteredFileBundle> bundle = new HashedMap<>();
                sixFilteredPollerRepo.deleteEntriesBySixListReference(childList.getReference());
                log.info("Deleted entries from six filtered poller");
                List<FilteredInstrumentFile> i = filteredInstrumentFileRepository.getByListRef(childList.getReference());
                log.info("Fetched filtered instrument data for list ref {}", childList.getReference());
                findBatchIdAndProgressActivity(rows);
                List<FilteredStructuredFile> s = filteredStructureFileRepository.getByListRef(childList.getReference());
                log.info("Fetched filtered structure data for list ref {}", childList.getReference());
                findBatchIdAndProgressActivity(rows);
                deletePreviousEntryFromFilteredTable(childList.getReference());
                FilteredFileBundle filteredFileBundle = new FilteredFileBundle();
                findBatchIdAndProgressActivity(rows);

                log.info("Started apply generic filters for list ref {}", childList.getReference());
                filteredFileBundle = applyGenericFilters(i,s,filteredFileBundle, childList.getReference());
                log.info("Completed apply generic filters for list ref {}", childList.getReference());
                incrementProgress(batchJobExecutionIds);

                bundle.put(childList, filteredFileBundle);
                ListType listType = listTypeBuilder.generationOfListTypes(bundle, childList.getReference());
                sixXmlGenerationService.createAndUploadFile(listType, batchJobExecutionIds);
                finishBatchExecution(batchJobExecutionIds);
            }
        }
    }

    private void findBatchIdAndProgressActivity(List<SixFilteredPoller> rows) {
        Set<Long> batchExecutionsIds = rows.stream().map(SixFilteredPoller::getBatchJobExecutionId).collect(Collectors.toSet());
        if (!batchExecutionsIds.isEmpty()) {
            for (Long batchJobExecutionId : batchExecutionsIds) {
                batchProgressService.incrementExportBatchForSix(batchJobExecutionId, 1/3);
            }
        }
    }

    private void deletePreviousEntryFromFilteredTable(String reference) {
        filteredInstrumentFileRepository.deleteByListRef(reference, dbBatchSize);
        log.info("Deleting filtered instrument data in db of list ref {}, if exists", reference);

        filteredStructureFileRepository.deleteByListRef(reference, dbBatchSize);
        log.info("Deleting filtered structured data in db of list ref {}, if exists", reference);
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

    private void removeDuplicatedIsinBasedOnSanctioned(List<FilteredInstrumentFile> filteredInstru,
                                                       List<FilteredStructuredFile> filteredStructured) {
        Map<String, String> instrumentISINWithSanctionedDetail = filteredInstru.stream()
                .filter(i -> i.getIsin() != null)
                .collect(Collectors.toMap(
                        FilteredInstrumentFile::getIsin,
                        i -> i.getSixTargets().stream().map(FilteredSixTarget::getSanctioned).distinct()
                                .collect(Collectors.joining(" - "))));

        for(Map.Entry<String, String> instrumentISIN : instrumentISINWithSanctionedDetail.entrySet()) {

            if (instrumentISIN.getValue().toUpperCase().contains("YES")) {
                filteredStructured.removeIf(sf -> sf.getHostIsin().equals(instrumentISIN.getKey()));
            } else {
                List<FilteredStructuredFile> notSanctionedStruct = filteredStructured.stream()
                        .filter(sf -> sf.getHostIsin().equals(instrumentISIN.getValue())
                        && (sf.getSixTargets().stream().map(FilteredSixTarget::getSanctioned)
                                .collect(Collectors.joining(" - ")).toUpperCase().contains("NO"))).collect(Collectors.toList());

                if (!notSanctionedStruct.isEmpty()) {
                    filteredStructured.removeAll(notSanctionedStruct);
                }

                List<FilteredStructuredFile> sanctionedStruct = filteredStructured.stream().filter(sf -> sf.getHostIsin()
                        .equals(instrumentISIN.getValue())
                        && (sf.getSixTargets().stream().map(FilteredSixTarget::getSanctioned)
                        .collect(Collectors.joining(" - ")).toUpperCase().contains("YES"))).collect(Collectors.toList());

                if (!sanctionedStruct.isEmpty()) {
                    filteredInstru.removeIf(sf -> sf.getIsin().equals(instrumentISIN.getKey()));
                }
            }
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

    public void incrementProgress(Set<Long> batchJobExecutionIds) {
        for (Long batchJobExecutionId : batchJobExecutionIds) {
            batchProgressService.incrementExportBatchForSix(batchJobExecutionId, 15);
        }
    }

    private void finishBatchExecution(Set<Long> batchJobExecutionIds) {
        for (Long batchJobExecutionId : batchJobExecutionIds) {
            try{
                batchManagementService.markBatchExecutionFinishedBySys(batchJobExecutionId);
                batchManagementService.updateBatchExecutionCompletedProgress(batchJobExecutionId);
            }catch (Exception e) {
                log.error("could not update finished batchExecution {}", e.getMessage());
            }
        }
    }
}
