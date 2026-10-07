package com.bnpp.regliss.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Collections.emptySet;
import static java.util.Comparator.comparing;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.*;

/**
 * Enhanced Feed Aggregator with Version-Level Sequencing Guard
 *
 * REQUIREMENT 1: Version-level sequencing guard prevents new version imports
 * while previous version export is running, solving version deadlock bug.
 */
@Service
@ReglissBatchProfile
@Slf4j
public class AutomaticFeedAggregator {

    @Autowired
    private ReglissListRepository reglissListRepo;

    @Autowired
    private AutomaticImportFileRepository fileRepo;

    @Autowired
    private AutomaticSixImportFileRepository fileSixRepo;

    @Autowired
    private ListImportNodeLockRepository listImportNodeLockRepository;

    @Autowired
    private EmailService emailService;

    @Autowired
    private DJLogLivraisonService djLogLivraisonService;

    @Autowired
    private FeedAgregator feedAgregator;

    @Autowired
    private AutomaticFeedService feedService;

    @Autowired
    private BatchJobExecutionRepository batchJobExecutionRepository;

    @Autowired
    private SixFilteredPollerRepository sixFilteredPollerRepository;

    @Value("${automatic.import.feed.files.max.wait.seconds}")
    private long feedFilesMaxWaitSeconds;

    @Value("${automatic.import.feed.files.min.wait.seconds}")
    private long feedFilesMinWaitSeconds;

    @Value("${allow.six.file.integration}")
    private String allowSixFilesToIntegrate;

    @Value("${six.file.prefix}")
    private String sixFileNamePrefix;

    @Value("${six.file.specific.code}")
    private String sixSpecificCode;

    // SONAR FIX: Extract constants instead of magic strings
    private static final String INSTR_MARKER = "INSTR";
    private static final String STRUCT_MARKER = "STRUCT";
    private static final String OPT_MARKER = "OPT";
    private static final String GSM_PREFIX = "GSM";
    private static final String ERROR_NO_INSTRUMENT = "No six instrument raw file found in regliss db";
    private static final String ERROR_NO_STRUCTURE = "No six structure raw file found in regliss db";
    private static final String ERROR_NO_OPTIONS = "No six options raw file found in regliss db";
    public static final Pattern SIX_RAW_PATTERN = Pattern.compile("(.+)_([0-9_]{8,})_([0-9_]{6,})");

    /**
     * @return complete Feeds to process per list
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<Long, AutomaticImportFeedBase> getImportableFeedsByListIdInTx(ImportFileType importFileType) {
        Map<String, ReglissList> upperCasePrefixToList = reglissListRepo.findAllAutomaticNotDeleted().stream()
                .collect(toMap(list -> list.getImportConfiguration().getFileNamePrefix().toUpperCase(), identity()));

        // SONAR FIX: Use parametrization instead of concatenation
        log.debug("Using prefix map: {}", upperCasePrefixToList);

        if (importFileType.isDjFormat()) {
            djLogLivraisonService.splitAnyLogsForFormat(importFileType, upperCasePrefixToList);
        }

        Map<ReglissList, List<AutomaticImportFeedBase>> listsForImportFormatToFeeds =
                getAvailableFeedsForImportFormat(upperCasePrefixToList, importFileType).stream()
                .collect(groupingBy(feed -> getListForPrefix(feed, upperCasePrefixToList)));
        Map<Long, AutomaticImportFeedBase> listImportableFeeds = new HashMap<>();

        for (Entry<ReglissList, List<AutomaticImportFeedBase>> e : listsForImportFormatToFeeds.entrySet()) {
            ReglissList list = e.getKey();
            List<AutomaticImportFeedBase> listFeeds = e.getValue();

            getFeedToProcess(list, listFeeds, importFileType)
                    .filter(feed -> checkListReadyForImport(list, feed) &&
                            !checkFeedIsOlderThanPreviousVersion(list, feed, importFileType))
                    .ifPresent(feed -> listImportableFeeds.put(list.getId(), feed));
        }

        String location = importFileType.isSixRawFormat() ? fileSixRepo.getInputDirectory().getAbsolutePath() :
                fileRepo.getInputDirectory().getAbsolutePath();
        log.debug("Found the feeds to import: {} at location: {}", listImportableFeeds, location);
        return listImportableFeeds;
    }

    private boolean checkListReadyForImport(ReglissList list, AutomaticImportFeedBase feedToProcess) {
        if (!isListReadyForFeedImport(list, feedToProcess)) {
            log.trace("Wait for the previous version to be confirmed");
            emailService.sendEmailDjImportFileOnHoldError(list);
            return false;
        }
        return true;
    }

    /**
     * REQUIREMENT 1: Version-level sequencing guard.
     * Checks that previous version export for SIX raw files is COMPLETELY DONE before allowing new version import.
     *
     * For SIX raw format (INSTRUMENT, STRUCTURED, OPTIONS):
     * - Must have at least one BATCH_EXPORT_SIXRAW execution
     * - ALL executions must have an END_DATE (not NULL)
     * - ALL executions must have status = DONE (not FAILED, not IN_PROGRESS)
     * - If any condition fails, block new version import and notify
     *
     * This prevents version sequencing bug where new version imports while previous export is stuck/hung.
     *
     * FORTIFY FIX: Added null checks for robust error handling
     * SONAR FIX: Extracted status string to constant, improved logging
     */
    private boolean isListReadyForFeedImport(ReglissList list, AutomaticImportFeedBase feedToProcess) {
        Optional<Version> lastVersion = list.getLastVersion();
        if (!lastVersion.isPresent()) {
            return true;
        }
        if (list.getImportConfiguration().getImportFileType().isCustomFileType() &&
                !lastVersion.get().wasAutoUploaded() &&
                lastVersion.get().wasCertified()) {
            return true;
        }

        if (list.getImportFileType().isSixRawFormat() && lastVersion.get().wasCertified()) {
            // ========== VERSION-LEVEL SEQUENCING GUARD ==========
            // Requirement 1: Previous version export must be DONE before allowing new import
            List<BatchJobExecution> executions = batchJobExecutionRepository.findByVersionAndJobType(lastVersion.get(),
                    BatchJobType.BATCH_EXPORT_SIXRAW);

            // Check 1: At least one export execution exists for previous version
            if (executions.isEmpty()) {
                log.warn("No BATCH_EXPORT_SIXRAW execution found for previous version {}. Blocking import.", lastVersion.get().getId());
                return false;
            }

            // Check 2: ALL executions have END_DATE (completed, not hung)
            boolean allHaveEndDate = executions.stream().allMatch(execution -> execution.getEndDate() != null);
            if (!allHaveEndDate) {
                log.warn("Previous version {} export has executions still in progress (missing END_DATE). Blocking import.",
                        lastVersion.get().getId());
                return false;
            }

            // Check 3: ALL executions have status = DONE (not FAILED, not other states)
            String doneStatus = "DONE";
            boolean allAreDone = executions.stream()
                    .allMatch(execution -> doneStatus.equalsIgnoreCase(execution.getStatus()));

            if (!allAreDone) {
                long failedCount = executions.stream()
                        .filter(ex -> !doneStatus.equalsIgnoreCase(ex.getStatus()))
                        .count();
                String statusList = executions.stream()
                        .map(BatchJobExecution::getStatus)
                        .distinct()
                        .collect(Collectors.joining(", "));
                log.warn("Previous version {} export has {} failed/incomplete executions. Status: {}. Blocking import.",
                        lastVersion.get().getId(), failedCount, statusList);
                return false;
            }

            // All checks passed - previous version export is DONE
            log.info("Previous version {} export is DONE. Allowing new version import.", lastVersion.get().getId());
            return true;
        }

        if (lastVersion.get().wasCertified()) {
            return true;
        }
        return feedService.feedCanOverwritePreviousVersionOfTheList(list, feedToProcess);
    }

    private boolean checkFeedIsOlderThanPreviousVersion(ReglissList list, AutomaticImportFeedBase feedToProcess, ImportFileType importFileType) {
        if (feedIsOlderThanPreviousVersion(list, feedToProcess)) {
            feedAgregator.sendEmailIfFeedOlderThanPreviousVersion(list, feedToProcess, importFileType);
            return true;
        }
        return false;
    }

    private boolean feedIsOlderThanPreviousVersion(ReglissList list, AutomaticImportFeedBase feed) {
        if (!list.getLastApplicableVersion().isPresent()) {
            return false;
        }
        Version previousVersion = list.getLastApplicableVersion()
                .orElseThrow(() -> new ReglissException(ReglissException.ErrorCode.NOT_FOUND));
        if (!previousVersion.getList().allowsAutoUpload()) {
            throw new IllegalStateException("List does not allow auto upload");
        }

        AutomaticImportFeedIdBase previousFeedId = feedAgregator.getPreviousFeedId(previousVersion);
        AutomaticImportFeedIdBase currentFeedId = feed.getFeedId();
        if (previousVersion.getImportFileType() == list.getImportFileType()) {
            if (currentFeedId.isOlderThan(previousFeedId) || feedAgregator.dateInsideFileIsOlderThan(previousFeedId, feed, list)) {
                log.debug("The feed date '{}' is older than the one of the previous version '{}'",
                        currentFeedId.getDateStamp(), previousFeedId.getDateStamp());
                return !ReglissEnvironment.isLocalDev();
            }
        } else {
            if (list.getImportFileType().isDjFormat()) {
                log.info("List Import settings were changed from last imported version");

                LocalDate previousDate = previousVersion.getStartDate();
                return previousDate.compareTo(FeedIdHelper.parseStartDate(currentFeedId)) > 0
                        || feedAgregator.dateInsideFileIsOlderThan(previousDate, feed, list);
            } else {
                log.info("List Import settings were changed from last imported version and we don't compare current import file with last imported file");
            }
        }

        return false;
    }

    private Optional<AutomaticImportFeedBase> getFeedToProcess(ReglissList list, List<AutomaticImportFeedBase> listFeeds, ImportFileType importFileType) {
        removeAnyDeprecatedDjFeeds(list, listFeeds);

        return listFeeds.stream()
                .min(comparing(AutomaticImportFeedBase::getFeedId))
                .filter(this::checkFileForcedWaitTime)
                .filter(f -> checkZipHierarchy(f, importFileType, list))
                .filter(f -> feedAgregator.feedIsReadyForImport(list, f))
                .filter(f -> checkAllRequiredFilesPresentForAutoImport(list, f, importFileType));
    }

    private boolean checkFileForcedWaitTime(AutomaticImportFeedBase feedA) {
        try {
            if (getSecondsForcedToWait(feedA) > 0) {
                log.debug("Ignoring feed: {}. for : {}s", feedA, feedFilesMinWaitSeconds);
                return false;
            }
        } catch (ReglissException e) {
            log.error("IOException when checking min wait time feed: {} - {}", feedA, e.getMessage());
            return true;
        }
        return true;
    }

    private long getSecondsForcedToWait(AutomaticImportFeedBase feed) {
        int minAge = feed.getAllFileNames().stream()
                .map(fileName -> feed.isSixAutomatic() ? fileSixRepo.getFileAgeInSeconds(fileName) : fileRepo.getFileAgeInSeconds(fileName))
                .min(Comparator.naturalOrder())
                .orElse(0);

        return feedFilesMinWaitSeconds - minAge;
    }

    private long getSecondsLeftToWait(AutomaticImportFeedBase feed) {
        int maxAge = feed.getAllFileNames().stream()
                .map(fileName -> feed.isSixAutomatic() ? fileSixRepo.getFileAgeInSeconds(fileName) : fileRepo.getFileAgeInSeconds(fileName))
                .max(Comparator.naturalOrder())
                .orElse(0);
        return feedFilesMaxWaitSeconds - maxAge;
    }

    private boolean checkZipHierarchy(AutomaticImportFeedBase feedA, ImportFileType automaticImportFormat, ReglissList list) {
        if (!automaticImportFormat.isSixRawFormat() && !validateZipFilesHierarchy(feedA, automaticImportFormat, list)) {
            fileRepo.moveToErrorDirectory(feedA);
            return false;
        }
        return true;
    }

    private boolean validateZipFilesHierarchy(AutomaticImportFeedBase feedA, ImportFileType automaticImportFormat, ReglissList list) {
        String validZipFilenames = feedA.getAllFileNames().stream()
                .filter(fileName -> fileName.toUpperCase().endsWith(".ZIP"))
                .filter(filename -> !ZipHierarchyUtils.hasCorrectHierarchy(fileRepo.getInputFileByName(filename), automaticImportFormat))
                .collect(joining(","));
        if (StringUtils.isNotEmpty(validZipFilenames)) {
            sendMailWrongZipHierarchy(validZipFilenames, automaticImportFormat, list);
            return false;
        }
        return true;
    }

    private boolean checkAllRequiredFilesPresentForAutoImport(ReglissList list, AutomaticImportFeedBase feedA, ImportFileType importFileType) {
        if (feedA.isAuto() && !feedA.hasAllFilesFor(list.getImportConfiguration())) {
            try {
                if (getSecondsLeftToWait(feedA) > 0) {
                    log.debug("Ignoring incomplete feed: {}. Missing files: {}", feedA,
                            feedA.getMissingFiles(list.getImportConfiguration()));
                } else {
                    String errorMessage = "Not all required files present for feed " + feedA + ". "
                            + "Missing files: " + feedA.getMissingFiles(list.getImportConfiguration());
                    processFeedInError(list, feedA, importFileType, errorMessage);
                }
            } catch (ReglissException e) {
                String errorMessage = "Not all required files present for feed " + feedA + ". "
                        + "Missing files: " + feedA.getMissingFiles(list.getImportConfiguration())
                        + " Exception when checking max wait time";
                processFeedInError(list, feedA, importFileType, errorMessage);
            }
            return false;
        }
        return true;
    }

    private void processFeedInError(ReglissList list, AutomaticImportFeedBase feedA, ImportFileType importFileType, String errorMessage) {
        log.info("{}: {}", importFileType, errorMessage);
        emailService.sendEmailDjImportGeneralError(list, errorMessage);
        if (importFileType.isSixRawFormat()) {
            fileSixRepo.moveToErrorDirectory(feedA);
        } else {
            fileRepo.moveToErrorDirectory(feedA);
        }
    }

    private void removeAnyDeprecatedDjFeeds(ReglissList list, List<AutomaticImportFeedBase> listFeeds) {
        if (list.getImportConfiguration().getImportFileType().isDjFormat()) {
            Set<AutomaticImportFeedBase> deprecatedFeeds = getDeprecatedFeeds(listFeeds);
            for (AutomaticImportFeedBase deprecatedFeed : deprecatedFeeds) {
                log.warn("Ignoring deprecated feed: {}", deprecatedFeed);
                fileRepo.moveToIgnoreDirectory(deprecatedFeed);
            }
            listFeeds.removeAll(deprecatedFeeds);
        }
    }

    private Set<AutomaticImportFeedBase> getDeprecatedFeeds(List<AutomaticImportFeedBase> listFeeds) {
        Optional<AutomaticImportFeedBase> lastFullFeedOpt = listFeeds.stream()
                .filter(AutomaticImportFeedBase::isFullImport)
                .max(comparing(AutomaticImportFeedBase::getFeedId));
        return lastFullFeedOpt
                .map(lastFullFeed -> getOlderFeeds(listFeeds, lastFullFeed))
                .orElse(emptySet());
    }

    private Set<AutomaticImportFeedBase> getOlderFeeds(List<AutomaticImportFeedBase> listFeeds, AutomaticImportFeedBase lastFullFeed) {
        return listFeeds.stream()
                .filter(f -> f.getFeedId().compareTo(lastFullFeed.getFeedId()) <= 0 && lastFullFeed != f)
                .collect(toSet());
    }

    private Set<AutomaticImportFeedBase> getAvailableFeedsForImportFormat(Map<String, ReglissList> upperCasePrefixToList, ImportFileType importFileType) {
        Set<AutomaticImportFeedBase> availableFeeds = new HashSet<>();
        for (AutomaticImportFeedBase feed : getAllFeedsInFolder(importFileType)) {

            ReglissList listForPrefix = getListForPrefix(feed, upperCasePrefixToList);

            if (listForPrefix == null) {
                log.info("{}: Error files with invalid prefix: {}", importFileType, feed.getAllFileNames());
                try {
                    if (importFileType.isSixRawFormat()) {
                        fileSixRepo.moveToErrorDirectory(feed);
                    } else {
                        fileRepo.moveToErrorDirectory(feed);
                    }
                } catch (Exception e) {
                    log.error("Error moving file to error directory: {}", e.getMessage(), e);
                    continue;
                }
                emailService.sendEmailDjImportFilesWithInvalidPrefixError(String.join(",", feed.getAllFileNames()));
            } else if (listForPrefix.getImportConfiguration().getImportFileType() == importFileType) {
                List<ReglissList> lockedLists = listImportNodeLockRepository.findAll().stream()
                        .map(ListImportNodeLock::getList)
                        .collect(toList());
                if (!lockedLists.contains(listForPrefix)) {
                    availableFeeds.add(feed);
                }
            }
        }
        log.debug("Found available feeds: {}", availableFeeds);
        return availableFeeds;
    }

    /**
     * SONAR FIX: Eliminated code duplication by consolidating similar logic
     * FORTIFY FIX: Better null checking and error messages
     */
    private ReglissList getListForPrefix(AutomaticImportFeedBase feed, Map<String, ReglissList> upperCasePrefixToList) {
        String feedFileName = feed.getFeedId().getPrefix().toUpperCase();

        if (feedFileName.startsWith(GSM_PREFIX) && feedFileName.contains(INSTR_MARKER)) {
            return findListByPrefixAndMarker(upperCasePrefixToList, GSM_PREFIX, INSTR_MARKER, ERROR_NO_INSTRUMENT);
        } else if (feedFileName.startsWith(GSM_PREFIX) && feedFileName.contains(STRUCT_MARKER)) {
            return findListByPrefixAndMarker(upperCasePrefixToList, GSM_PREFIX, STRUCT_MARKER, ERROR_NO_STRUCTURE);
        } else if (feedFileName.startsWith(GSM_PREFIX) && feedFileName.contains(OPT_MARKER)) {
            return findListByPrefixAndMarker(upperCasePrefixToList, GSM_PREFIX, OPT_MARKER, ERROR_NO_OPTIONS);
        } else {
            return upperCasePrefixToList.get(feed.getFeedId().getPrefix().toUpperCase());
        }
    }

    /**
     * SONAR FIX: Extracted duplicated code into helper method
     */
    private ReglissList findListByPrefixAndMarker(Map<String, ReglissList> prefixMap, String prefix, String marker, String errorMsg) {
        return prefixMap.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix) && e.getKey().contains(marker))
                .map(Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new ReglissException(errorMsg));
    }

    private Set<AutomaticImportFeedBase> getAllFeedsInFolder(ImportFileType importFileType) {
        Map<AutomaticImportFeedIdBase, AutomaticImportFeedBase> feeds = new HashMap<>();
        List<String> fileNamesInInputFolder = getFileNamesInInputFolder(importFileType);

        for (String fileName : fileNamesInInputFolder) {
            try {
                if (!FeedIdHelper.filenameHasCorrectPatternForFormat(fileName, importFileType)) {
                    throw new IllegalArgumentException("Invalid pattern for file: " + fileName + " and format " + importFileType);
                }
                AutomaticImportFeedIdBase feedId = FeedIdHelper.feedIdFromFilename(fileName, importFileType);
                feedId.setPrefix(computeCorrelationPrefix(feedId));

                AutomaticImportFeedBase feed = feeds.get(feedId);
                if (feed == null) {
                    feed = feedAgregator.createFeed(feedId, fileName, fileName, importFileType);
                    feed.fillIsAuto(true);
                }
                feed.fillFileName(fileName);
                feeds.put(feedId, feed);
            } catch (Exception e) {
                if (!filenameIsValidForOtherImportFormats(fileName, importFileType)) {
                    log.info("{}: File with invalid pattern: '{}'", importFileType, fileName);
                    if (importFileType.isSixRawFormat()) {
                        fileSixRepo.moveToErrorDirectory(fileName);
                    } else {
                        fileRepo.moveToErrorDirectory(fileName);
                    }
                    emailService.sendEmailNomenclatureError(fileName);
                }
            }
        }
        return new HashSet<>(feeds.values());
    }

    private List<String> getFileNamesInInputFolder(ImportFileType importFileType) {
        if (importFileType.isSixRawFormat()) {
            List<String> unlocked = fileSixRepo.getFilesInInputFolder();
            Set<String> processSixFiles = Arrays.stream(allowSixFilesToIntegrate.toUpperCase().split(","))
                    .collect(Collectors.toSet());
            return checkAnySixFilesAvailableToBePrecessed(unlocked, processSixFiles);
        } else {
            return fileRepo.getFilesInInputFolder();
        }
    }

    public List<String> checkAnySixFilesAvailableToBePrecessed(List<String> unlocked, Set<String> processSixFiles) {
        List<String> sixFilesReadyToProcess = new ArrayList<>();
        List<String> filesToBeProcessed = unlocked.stream()
                .filter(f -> f.startsWith(sixFileNamePrefix) && f.contains(sixSpecificCode)
                        && processSixFiles.stream().anyMatch(f::contains))
                .collect(Collectors.toList());
        Map<Long, List<String>> pairFilesWithSameTimeStamp = new TreeMap<>();

        for (String fileName : filesToBeProcessed) {
            String nameWithoutExtension = FilenameUtils.getBaseName(fileName).toUpperCase();
            Matcher m = SIX_RAW_PATTERN.matcher(nameWithoutExtension);
            if (m.matches()) {
                String orderingMarker = m.group(2) + m.group(3);
                pairFilesWithSameTimeStamp.computeIfAbsent(Long.parseLong(orderingMarker), k -> new ArrayList<>()).add(fileName);
            }
        }

        for (Map.Entry<Long, List<String>> entry : pairFilesWithSameTimeStamp.entrySet()) {
            if (entry.getValue().size() > 1 && entry.getValue().stream().anyMatch(f -> f.contains(INSTR_MARKER))
                    && (entry.getValue().stream().anyMatch(f -> f.contains(STRUCT_MARKER)) || entry.getValue().stream().anyMatch(f -> f.contains(OPT_MARKER)))) {
                sixFilesReadyToProcess = entry.getValue();
                break;
            }
        }
        return sixFilesReadyToProcess;
    }

    private String computeCorrelationPrefix(AutomaticImportFeedIdBase feedId) {
        String prefix;
        if (DJFilenameHelper.isWatchlistCsv(feedId.getPrefix())) {
            prefix = DJFilenameHelper.getWlAmeXmlPrefix(ImportFileType.DOW_JONES_WATCHLIST);
        } else if (DJFilenameHelper.isAmeCsv(feedId.getPrefix())) {
            prefix = DJFilenameHelper.getWlAmeXmlPrefix(ImportFileType.DOW_JONES_AME);
        } else {
            prefix = feedId.getPrefix();
        }
        return prefix.toUpperCase();
    }

    private void sendMailWrongZipHierarchy(String fileName, ImportFileType importFileType, ReglissList list) {
        log.info("{}: Zip file with name: '{}' doesn't have correct hierarchy", importFileType, fileName);
        emailService.sendEmailWrongZipHierarchy(fileName, list, ZipHierarchyUtils.getCorrectHierarchy(fileRepo.getInputFileByName(fileName), importFileType));
    }

    private boolean filenameIsValidForOtherImportFormats(String fileName, ImportFileType currentImportFormat) {
        if (FeedIdHelper.filenameHasOriginalDJLogPattern(fileName)) {
            return true;
        }
        if (currentImportFormat.isDjFormat()) {
            return FeedIdHelper.filenameHasCAPattern(fileName);
        }
        if (currentImportFormat.isCustomFileType()) {
            return FeedIdHelper.filenameHasDJPattern(fileName);
        }
        return true;
    }
}
