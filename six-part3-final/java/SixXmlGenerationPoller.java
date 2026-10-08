package com.bnpp.regliss.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
// BatchManagementService, BatchJobExecution, BatchJobExecutionRepository, HeartbeatService, EmailService,
// SixFilteredStore, SixFilteredPoller, SixExportProgress, SixExportException, SixExportRunGuard

/**
 * Export step 2: when every file type of allow.six.file.integration was filtered for an output list, builds its
 * CONVERTER-...xml file (generic rules + ListTypeBuilder + SixXmlGenerationService, all unchanged), and when every
 * list of a SIX delivery is finished, places ALL the files of that delivery into the DJ IN folder together.
 *
 * Part 3 (kept):
 *  - a list's file is built as soon as every file type of that list is ready (951 can be built while 952 is still
 *    being filtered);
 *  - exactly once on two servers: the READY rows of a list are claimed (FOR UPDATE SKIP LOCKED) and get STATUS
 *    BUILDING in the same transaction;
 *  - an error while building ONE file: logged with all technical details, short alert mail, the list is DROPPED and
 *    its jobs stay below 100 % (KO); the server continues with the next ready list;
 *  - a list that failed while filtering / writing on a server is never built: its ready rows are DROPPED;
 *  - progress: 20 % of each list's share per file, at most 95 % automatically;
 *  - filtered rows read with their targets in ONE query per table; O(n) duplicate-by-sanction rule (same result).
 *
 * Export phase 1 (new):
 *  - HOLD: a built file goes to the holding folder of its delivery (SixXmlGenerationService), STATUS BUILT;
 *  - RELEASE (every run, after the builds): a delivery is released when it has BUILT lists and none of its lists is
 *    still in progress. One server locks the BUILT rows (FOR UPDATE SKIP LOCKED), moves all the held files into DJ IN
 *    and marks them DONE in the same transaction. Lists that failed are not waited for and not delivered;
 *  - "in progress" (no status column on the job, existing rules only):
 *      PENDING  : its job is alive: not finished, its server's heartbeat is alive (HeartbeatService.isNodeAlive) and
 *                 it was updated in the last 30 minutes (the filter step refreshes it every minute);
 *      READY    : the list is complete (built in this or the next run) or its missing file type is PENDING on an
 *                 alive job;
 *      BUILDING : the build touched UPDATED_TIME in the last 30 minutes;
 *    rows that are not in progress any more (server crash ...) are DROPPED at the release;
 *  - 100 % + end date when every list of the job is DONE (in DJ IN), so after the release (before: after the build);
 *  - the jobs of the held files are kept alive while the delivery waits for a list in progress;
 *  - the filtered rows of a list that will never be built are removed at once (not at the nightly cleanup);
 *  - memory: the filtered rows are released before the XML is written, the XML is written straight to the file;
 *    an OutOfMemoryError while building one list fails only that list (mail), the server continues.
 */
@ReglissBatchProfile
@Component
@Slf4j
public class SixXmlGenerationPoller {

    /** The UI shows a job without end date as KO when it was not updated for 30 minutes. */
    private static final long KO_AFTER_MINUTES = 30;

    private static final DateTimeFormatter INPUT_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter OUTPUT_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

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
    private HeartbeatService heartbeatService;
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
        try {
            releaseDeliveries(childLists, required);
        } catch (RuntimeException e) {
            // database not reachable ...: the held files stay in the holding folder, the next run tries again
            log.error("SIX XML step: held CONVERTER files not released in this run: {}", SixExportException.rootCause(e), e);
        }
    }

    // ------------------------------------------------------------------------------------------ build (one list)

    private void generateXmlFile(ReglissList childList, Set<String> required) {
        String reference = childList.getReference();
        dropRowsOfFailedList(reference, required);

        SixFilteredStore.PollerClaim claim = sixFilteredStore.claimPollerRows(reference, required);
        if (!claim.isClaimed()) {
            keepWaitingJobsAlive(reference, claim.getRows());
            return;
        }
        Build build = new Build(reference, claim.getRows());
        if (!build.superseded.isEmpty()) {
            sixFilteredStore.markBuildFailed(SixFilteredStore.ids(build.superseded));
            log.warn("SIX XML step: list {}: rows of an older delivery dropped, replaced by delivery {}: {}", reference,
                    build.delivery, build.superseded);
        }

        // the list may have failed on a server between the drop above and the claim (a complete list is still
        // built when only the delivery was stopped)
        Optional<SixFilteredPoller> stop = stopOf(reference, build.rows)
                .filter(f -> !SixExportRunGuard.FAILED_ALL.equals(f.getStatus()));
        if (stop.isPresent()) {
            sixFilteredStore.markBuildFailed(build.rowIds);
            log.error("SIX XML step: {} not generated because {}", build.where, sixExportRunGuard.describe(stop.get()));
            return;
        }

        log.info("Data filtering completed: generating the {}", build.where);
        long start = System.currentTimeMillis();
        try {
            // the filtered rows are only referenced inside buildListType: they can be freed before the XML is written
            ListType listType = buildListType(childList, build);
            keepAlive(build);
            Path file = stage(SixExportException.Stage.WRITE_FILE, build,
                    () -> sixXmlGenerationService.createHeldFileOrThrow(listType, build.jobIds, build.delivery, reference));
            sixFilteredStore.markBuilt(build.rowIds);
            log.info("{} generated as {} in {} ms, held until every list of delivery {} is finished", build.where,
                    file.getFileName(), System.currentTimeMillis() - start, build.delivery);
        } catch (SixExportException e) {
            failBuild(childList, build, e);
            return;
        } catch (OutOfMemoryError e) { // NOSONAR only this list fails: its data are unreachable here, the server continues
            failBuild(childList, build, new SixExportException(SixExportException.Stage.BUILD_XML, build.where, reference,
                    null, build.firstJob(), e));
            return;
        }
        for (Long job : build.jobIds) {
            addXmlProgress(job);
        }
    }

    /** Reads the filtered rows, applies the generic rules and builds the XML records of one list. */
    private ListType buildListType(ReglissList childList, Build build) {
        String reference = childList.getReference();
        List<FilteredInstrumentFile> i = stage(SixExportException.Stage.READ_FILTERED, build,
                () -> new ArrayList<>(filteredInstrumentFileRepository.findLatestByListRefWithTargets(reference)));
        keepAlive(build);
        List<FilteredStructuredFile> s = stage(SixExportException.Stage.READ_FILTERED, build,
                () -> new ArrayList<>(filteredStructureFileRepository.findLatestByListRefWithTargets(reference)));
        log.info("Fetched {} filtered instrument rows and {} filtered structure rows for list ref {}", i.size(), s.size(), reference);
        keepAlive(build);

        FilteredFileBundle filteredFileBundle = stage(SixExportException.Stage.GENERIC_RULES, build,
                () -> applyGenericFilters(i, s, new FilteredFileBundle(), reference));
        keepAlive(build);

        return stage(SixExportException.Stage.BUILD_XML, build, () -> {
            Map<ReglissList, FilteredFileBundle> bundle = new HashMap<>();
            bundle.put(childList, filteredFileBundle);
            return listTypeBuilder.generationOfListTypes(bundle, reference);
        });
    }

    /** This list only: its jobs stay below 100 % (KO), the other lists continue and are still delivered. */
    private void failBuild(ReglissList childList, Build build, SixExportException e) {
        log.error(e.getMessage(), e);
        quietly("mark the list as failed", () -> sixFilteredStore.markBuildFailed(build.rowIds));
        quietly("send the alert mail", () -> emailService.sendEmailDjImportGeneralError(childList, e.mailText()));
    }

    /** Runs one stage of the XML step; any error becomes a SixExportException that says where it stopped. */
    private <T> T stage(SixExportException.Stage stage, Build build, Supplier<T> work) {
        try {
            return work.get();
        } catch (RuntimeException e) {
            throw new SixExportException(stage, build.where, build.reference, null, build.firstJob(), e);
        }
    }

    /** The claimed rows of one list: the rows of its newest delivery (rows of an older delivery are superseded). */
    private final class Build {
        final String reference;
        final String delivery;
        final List<SixFilteredStore.PollerRow> rows;
        final List<SixFilteredStore.PollerRow> superseded;
        final List<Long> rowIds;
        final Set<Long> jobIds;
        final String where;

        Build(String reference, List<SixFilteredStore.PollerRow> claimed) {
            this.reference = reference;
            Long newestVersion = claimed.stream().map(SixFilteredStore.PollerRow::getRawVersionId).filter(Objects::nonNull)
                    .max(Comparator.naturalOrder()).orElse(null);
            this.delivery = deliveryOf(newestVersion);
            Map<Boolean, List<SixFilteredStore.PollerRow>> byDelivery = claimed.stream()
                    .collect(Collectors.partitioningBy(r -> delivery.equals(deliveryOf(r.getRawVersionId()))));
            this.rows = byDelivery.get(true);
            this.superseded = byDelivery.get(false);
            this.rowIds = SixFilteredStore.ids(rows);
            this.jobIds = SixFilteredStore.jobIdsOf(rows);
            this.where = "CONVERTER file of list " + reference + " (" + rows.stream().map(r -> r.getFileType() + "(v"
                    + r.getRawVersionId() + ")").collect(Collectors.joining(", ")) + ", delivery " + delivery + ", jobs " + jobIds + ")";
        }

        Long firstJob() {
            return jobIds.isEmpty() ? null : jobIds.iterator().next();
        }
    }

    /** Delivery of a raw version (date+time of its SIX file, SixExportRunGuard); rows without version: "unknown". */
    private String deliveryOf(Long rawVersionId) {
        return rawVersionId == null ? "unknown" : sixExportRunGuard.deliveryOf(rawVersionId);
    }

    /**
     * Ready rows that will never become a file are DROPPED, and their filtered rows removed:
     *  - the list failed (FAILED of this list, on either server);
     *  - the delivery was stopped (FAILED_ALL) and the list is incomplete with nothing left to filter
     *    (no PENDING row): the missing file type will never come. A COMPLETE list is still built.
     */
    private void dropRowsOfFailedList(String reference, Set<String> required) {
        List<SixFilteredStore.PollerRow> rows = sixFilteredStore.readyRows(reference);
        if (rows.isEmpty()) {
            return;
        }
        boolean complete = rows.stream().map(SixFilteredStore.PollerRow::getFileType).collect(Collectors.toSet()).containsAll(required);
        List<SixFilteredStore.PollerRow> dropped = new ArrayList<>();
        String reason = null;
        for (SixFilteredStore.PollerRow row : rows) {
            Optional<SixFilteredPoller> stop = stopOf(reference, Collections.singletonList(row));
            boolean deliveryStopped = stop.isPresent() && SixExportRunGuard.FAILED_ALL.equals(stop.get().getStatus());
            if (stop.isPresent() && (!deliveryStopped || (!complete && sixFilteredStore.jobsStillFiltering(reference).isEmpty()))) {
                dropped.add(row);
                reason = sixExportRunGuard.describe(stop.get());
            }
        }
        if (!dropped.isEmpty()) {
            sixFilteredStore.dropReady(SixFilteredStore.ids(dropped));
            log.error("SIX XML step: CONVERTER file of list {} not generated (rows dropped) because {}", reference, reason);
            removeFilteredRows(dropped);
        }
    }

    /** Filtered rows (FILTERED_SIX_*) of dropped list rows: never used, removed now (kept if the list is in use again). */
    private void removeFilteredRows(List<SixFilteredStore.PollerRow> droppedRows) {
        for (SixFilteredStore.PollerRow row : droppedRows) {
            quietly("remove the filtered rows of " + row, () -> {
                int removed = sixFilteredStore.removeFilteredRowsOfDroppedRow(row);
                if (removed >= 0) {
                    log.info("SIX export: {} filtered rows removed for dropped {}", removed, row);
                }
            });
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
            keepAlive(SixFilteredStore.jobIdsOf(waitingRows));
        }
    }

    /**
     * XML share of one list = 20 % of the list share (90 % / number of lists of the job). The automatic total never
     * passes 95 %; 100 % and the end date are set when every list of the job is in DJ IN (release).
     */
    private void addXmlProgress(Long job) {
        try {
            SixFilteredStore.JobLists lists = sixFilteredStore.jobLists(job);
            double current = batchJobExecutionRepository.findById(job).map(j -> percentOf(j.getPercent())).orElse(0.0);
            double target = Math.min(SixExportProgress.AUTOMATIC_MAX,
                    current + SixExportProgress.listShare(lists.getTotal()) * SixExportProgress.XML_PART);
            int increment = (int) Math.floor(target) - (int) Math.floor(current);
            batchProgressService.incrementExportBatchForSix(job, Math.max(0, increment));
        } catch (RuntimeException e) {
            log.error("SIX XML step: could not update the progress of job {}: {}", job, SixExportException.rootCause(e), e);
        }
    }

    // ------------------------------------------------------------------------------------------ release (delivery)

    /**
     * Every delivery with held files (BUILT) whose lists are all finished: its files go into DJ IN together.
     * Several deliveries are independent (normally only one runs at a time: the import of the next delivery waits
     * for the end of this export, AutomaticFeedAggregator).
     */
    private void releaseDeliveries(List<ReglissList> childLists, Set<String> required) {
        List<SixFilteredStore.PollerRow> built = sixFilteredStore.rowsInStatus(SixFilteredStore.BUILT);
        if (built.isEmpty()) {
            return;
        }
        List<SixFilteredStore.PollerRow> open = sixFilteredStore.rowsInStatus(SixFilteredStore.PENDING,
                SixFilteredStore.READY, SixFilteredStore.BUILDING);
        Map<String, ReglissList> listsByRef = childLists.stream()
                .collect(Collectors.toMap(ReglissList::getReference, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Map<String, List<SixFilteredStore.PollerRow>> builtByDelivery = byDelivery(built);
        Map<String, List<SixFilteredStore.PollerRow>> openByDelivery = byDelivery(open);
        for (Map.Entry<String, List<SixFilteredStore.PollerRow>> delivery : builtByDelivery.entrySet()) {
            try {
                Liveness liveness = new Liveness(listsByRef.keySet(), required);
                List<SixFilteredStore.PollerRow> openOfDelivery = openByDelivery.getOrDefault(delivery.getKey(), Collections.emptyList());
                releaseDelivery(delivery.getKey(), delivery.getValue(), openOfDelivery, listsByRef, required, liveness);
            } catch (RuntimeException e) {
                log.error("SIX XML step: delivery {} not released in this run (next run tries again): {}", delivery.getKey(),
                        SixExportException.rootCause(e), e);
            }
        }
    }

    private void releaseDelivery(String delivery, List<SixFilteredStore.PollerRow> built,
                                 List<SixFilteredStore.PollerRow> open, Map<String, ReglissList> listsByRef, Set<String> required,
                                 Liveness liveness) {
        List<SixFilteredStore.PollerRow> inProgress = liveness.inProgress(open);
        if (!inProgress.isEmpty()) {
            keepHeldJobsAlive(built, open);
            log.debug("SIX delivery {}: {} CONVERTER file(s) held, waiting for {}", delivery, listRefs(built), inProgress);
            return;
        }
        Release release = sixFilteredStore.releaseInTx(SixFilteredStore.ids(built),
                locked -> moveHeldFiles(delivery, locked, listsByRef, required));
        if (release == null) {
            log.info("SIX delivery {}: release done by the other server", delivery);
            return;
        }
        if (!release.waitingFor.isEmpty()) {
            log.info("SIX delivery {}: not released, a list started meanwhile: {}", delivery, release.waitingFor);
            return;
        }
        afterRelease(delivery, release, listsByRef);
    }

    /**
     * In the release transaction (BUILT rows of the delivery locked): checks again that nothing is in progress, drops
     * the rows that will never be finished, moves every held file into DJ IN and marks its list DONE. A file that
     * cannot be moved: its list is DROPPED (mail after the commit), the other files are still delivered.
     */
    private Release moveHeldFiles(String delivery, List<SixFilteredStore.PollerRow> locked, Map<String, ReglissList> listsByRef,
                                  Set<String> required) {
        Release release = new Release();
        List<SixFilteredStore.PollerRow> open = byDelivery(sixFilteredStore.rowsInStatus(SixFilteredStore.PENDING,
                SixFilteredStore.READY, SixFilteredStore.BUILDING)).getOrDefault(delivery, Collections.emptyList());
        Liveness liveness = new Liveness(listsByRef.keySet(), required);
        release.waitingFor.addAll(liveness.inProgress(open));
        if (!release.waitingFor.isEmpty()) {
            return release;
        }
        release.unfinished.addAll(open);
        if (!open.isEmpty()) {
            sixFilteredStore.dropUnfinished(SixFilteredStore.ids(open));
        }
        Map<String, List<SixFilteredStore.PollerRow>> byList = locked.stream()
                .collect(Collectors.groupingBy(SixFilteredStore.PollerRow::getListRef, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<String, List<SixFilteredStore.PollerRow>> list : byList.entrySet()) {
            try {
                List<String> files = sixXmlGenerationService.releaseHeldFiles(delivery, list.getKey());
                if (files.isEmpty()) {
                    log.warn("SIX delivery {}: no held file for list {} (already moved by a run that stopped before recording it)",
                            delivery, list.getKey());
                }
                sixFilteredStore.markReleased(SixFilteredStore.ids(list.getValue()));
                release.delivered.put(list.getKey(), files);
            } catch (IOException | RuntimeException e) {
                sixFilteredStore.markReleaseFailed(SixFilteredStore.ids(list.getValue()));
                release.failed.put(list.getKey(), e);
            }
        }
        release.jobs.addAll(SixFilteredStore.jobIdsOf(locked));
        release.jobs.addAll(SixFilteredStore.jobIdsOf(open));
        return release;
    }

    /** After the commit: log, mails of the files that could not be moved, filtered rows of dropped lists, 100 %. */
    private void afterRelease(String delivery, Release release, Map<String, ReglissList> listsByRef) {
        log.info("SIX delivery {}: {} CONVERTER file(s) placed in DJ IN together: {}", delivery,
                release.delivered.values().stream().mapToInt(List::size).sum(), release.delivered);
        if (!release.unfinished.isEmpty()) {
            log.error("SIX delivery {}: lists not finished (their job stopped: server crash or restart), not delivered: {}",
                    delivery, release.unfinished);
            removeFilteredRows(release.unfinished);
        }
        for (Map.Entry<String, Exception> failed : release.failed.entrySet()) {
            Long job = release.jobs.isEmpty() ? null : release.jobs.iterator().next();
            SixExportException e = new SixExportException(SixExportException.Stage.RELEASE_FILE, "delivery " + delivery,
                    failed.getKey(), null, job, failed.getValue());
            log.error(e.getMessage(), e);
            ReglissList list = listsByRef.get(failed.getKey());
            if (list != null) {
                quietly("send the alert mail", () -> emailService.sendEmailDjImportGeneralError(list, e.mailText()));
            }
        }
        for (Long job : release.jobs) {
            finishIfDelivered(job);
        }
    }

    /** 100 % and end date (existing services) when every list of the job is in DJ IN. */
    private void finishIfDelivered(Long job) {
        try {
            SixFilteredStore.JobLists lists = sixFilteredStore.jobLists(job);
            if (lists.allDelivered()) {
                batchManagementService.markBatchExecutionFinishedBySys(job);
                batchManagementService.updateBatchExecutionCompletedProgress(job);
                log.info("SIX export job {}: all {} lists have their file in DJ IN - 100 %", job, lists.getTotal());
            } else {
                log.info("SIX export job {}: {} of {} lists have their file in DJ IN, {} failed - job stays below 100 %",
                        job, lists.getDone(), lists.getTotal(), lists.getDropped());
            }
        } catch (RuntimeException e) {
            log.error("SIX XML step: could not update job {}: {}", job, SixExportException.rootCause(e), e);
        }
    }

    /**
     * While a delivery waits for a list in progress, the jobs of its held files are refreshed (they are finished
     * otherwise and would turn KO). A job that still filters or builds is not refreshed here: it refreshes itself, so
     * its liveness always shows whether it really works (no job keeps itself alive through the waiting files).
     */
    private void keepHeldJobsAlive(List<SixFilteredStore.PollerRow> built, List<SixFilteredStore.PollerRow> open) {
        Set<Long> working = SixFilteredStore.jobIdsOf(open.stream()
                .filter(r -> !SixFilteredStore.READY.equals(r.getStatus())).collect(Collectors.toList()));
        Set<Long> idle = SixFilteredStore.jobIdsOf(built);
        idle.removeAll(working);
        keepAlive(idle);
    }

    /** Which open rows are still in progress (see the class comment); job liveness read once per job. */
    private final class Liveness {
        private final Set<String> currentLists;
        private final Set<String> required;
        private final Map<Long, Boolean> aliveByJob = new HashMap<>();
        private final LocalDateTime aliveSince = LocalDateTime.now().minusMinutes(KO_AFTER_MINUTES);

        Liveness(Set<String> currentLists, Set<String> required) {
            this.currentLists = currentLists;
            this.required = required;
        }

        List<SixFilteredStore.PollerRow> inProgress(List<SixFilteredStore.PollerRow> open) {
            return open.stream().filter(r -> isInProgress(r, open)).collect(Collectors.toList());
        }

        private boolean isInProgress(SixFilteredStore.PollerRow row, List<SixFilteredStore.PollerRow> open) {
            if (SixFilteredStore.PENDING.equals(row.getStatus())) {
                return jobAlive(row.getBatchJobExecutionId());
            }
            if (SixFilteredStore.READY.equals(row.getStatus())) {
                return currentLists.contains(row.getListRef())
                        && (complete(row.getListRef(), open) || missingTypeStillFiltered(row.getListRef(), open));
            }
            if (SixFilteredStore.BUILDING.equals(row.getStatus())) {
                return row.getUpdatedTime() != null && row.getUpdatedTime().isAfter(aliveSince);
            }
            return false;
        }

        /** Every required file type is READY: the list is built in this run or the next one. */
        private boolean complete(String listRef, List<SixFilteredStore.PollerRow> open) {
            return open.stream().filter(r -> SixFilteredStore.READY.equals(r.getStatus()) && listRef.equals(r.getListRef()))
                    .map(SixFilteredStore.PollerRow::getFileType).collect(Collectors.toSet()).containsAll(required);
        }

        private boolean missingTypeStillFiltered(String listRef, List<SixFilteredStore.PollerRow> open) {
            return open.stream().anyMatch(r -> SixFilteredStore.PENDING.equals(r.getStatus()) && listRef.equals(r.getListRef())
                    && jobAlive(r.getBatchJobExecutionId()));
        }

        private boolean jobAlive(Long jobId) {
            if (jobId == null) {
                return false;
            }
            return aliveByJob.computeIfAbsent(jobId, id -> batchJobExecutionRepository.findById(id)
                    .filter(job -> !job.isFinished() && job.getLastUpdateDate() != null && job.getLastUpdateDate().isAfter(aliveSince))
                    .filter(job -> heartbeatService.isNodeAlive(job.getNodeId()))
                    .isPresent());
        }
    }

    /** Result of the release of one delivery (filled in the release transaction, used after the commit). */
    private static final class Release {
        final List<SixFilteredStore.PollerRow> waitingFor = new ArrayList<>();
        final List<SixFilteredStore.PollerRow> unfinished = new ArrayList<>();
        final Map<String, List<String>> delivered = new LinkedHashMap<>();
        final Map<String, Exception> failed = new LinkedHashMap<>();
        final Set<Long> jobs = new LinkedHashSet<>();
    }

    private Map<String, List<SixFilteredStore.PollerRow>> byDelivery(List<SixFilteredStore.PollerRow> rows) {
        return rows.stream().collect(Collectors.groupingBy(r -> deliveryOf(r.getRawVersionId()), LinkedHashMap::new, Collectors.toList()));
    }

    private static Set<String> listRefs(List<SixFilteredStore.PollerRow> rows) {
        return rows.stream().map(SixFilteredStore.PollerRow::getListRef).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // ------------------------------------------------------------------------------------------ helpers

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

    /** Refreshes the jobs of a build, and the UPDATED_TIME of its BUILDING rows (shows that the build is alive). */
    private void keepAlive(Build build) {
        keepAlive(build.jobIds);
        quietly("refresh the BUILDING rows of list " + build.reference, () -> sixFilteredStore.touchBuilding(build.rowIds));
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
        LocalDate formattedDate = LocalDate.parse(dateValue, INPUT_FORMATTER);
        return formattedDate.format(OUTPUT_FORMATTER);
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
