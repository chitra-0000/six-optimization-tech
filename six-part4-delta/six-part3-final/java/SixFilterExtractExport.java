package com.bnpp.regliss.importer.six.extractor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

// TODO: re-add the project imports in the IDE (Alt+Enter / Optimize Imports) for:
// ReglissBatchProfile, CloseResourcesAfter, BatchExport, Version, ReglissList, SixFilters, SixSubFilters, SixFilteredPoller,
// SixFiltersRepository, ReglissListRepository, SixFilteredPollerRepository, BatchProgressService, EmailService,
// SixFileFilterService, SixFileKind, SixFilteredStore, SixFilteredRowWriter, SixExportProgress, SixExportException,
// SixExportRunGuard

/**
 * Export step 1 (called by the shared BatchExportPoller for SIX_FILTERED_FILE_GENERATION): filters ONE raw SIX file
 * (instrument, structured or options) for every output list and writes the FILTERED_SIX_* rows. The other raw file of
 * the delivery is filtered at the same time by the other server.
 *
 * 1. Start (once): output lists, the filters of every list (one snapshot for the whole run), CMIC / E014071 exclusions
 *    (computed once, only if a list needs them), one row per list in SIX_FILTERED_POLLER (FILE_TYPE = INSTR ...,
 *    STATUS = PENDING).
 *    -> An error here concerns every list: FAILED_ALL, both servers stop, one mail, job KO.
 * 2. Per list (any order): [other server stopped the delivery? -> stop] [list failed on the other server? -> skip it]
 *    filter -> delete previous rows of this version -> write -> announce (STATUS PENDING -> READY).
 *    -> An error in one list: partial rows removed, FAILED for that list (the other server skips / drops it), one mail,
 *       and the export CONTINUES with the next list. A "database not reachable" error stops everything (FAILED_ALL).
 * 3. The XML poller builds the file of a list as soon as all its file types are ready.
 * Export phase 1: every PENDING row carries the GENERATION_REASON of the job (GENERATION / REGENERATION). A
 *    regeneration waits, per list, while the automatic export of the same list and raw version is still working on it.
 *
 * Job status: 100 % + end date only when every list of the job has its file (SixXmlGenerationPoller). A failed or
 * stopped job keeps its percentage, gets no end date and becomes KO 30 minutes after its last update.
 * Log: full technical detail (stage, list, version, job, root cause, stack). Mail: short business text.
 */
@Component
@ReglissBatchProfile
@Slf4j
public class SixFilterExtractExport {

    /** A regeneration re-checks every minute whether the automatic export of the same list and version is finished. */
    @Value("${six.export.regeneration.wait.millis:60000}")
    private long waitForAutomaticExportMillis;

    @Autowired
    private SixFiltersRepository sixFiltersRepository;
    @Autowired
    private ReglissListRepository reglissListRepository;
    @Autowired
    private SixFileFilterService sixFileFilterService;
    @Autowired
    private SixFilteredPollerRepository sixFilteredPollerRepository;
    @Autowired
    private SixFilteredStore sixFilteredStore;
    @Autowired
    private SixFilteredRowWriter sixFilteredRowWriter;
    @Autowired
    private SixExportRunGuard sixExportRunGuard;
    @Autowired
    private BatchProgressService batchProgressService;
    @Autowired
    private EmailService emailService;

    @Value("${six.cmic.list}")
    private String sixCMICList;
    @Value("${six.e014071.list}")
    private String sixE014071List;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @CloseResourcesAfter
    public void filteringSixRawFiles(BatchExport batchExport) {
        log.info("Filtering six list files: {}", batchExport);
        Run run = new Run(batchExport.getBatchExecutionId(), new SixExportProgress(batchProgressService, batchExport.getBatchExecutionId()));
        run.progress.startKeepAlive();
        try {
            prepare(run, batchExport);
            for (ReglissList list : run.lists) {
                processList(run, list);
            }
            log.info("Filter six list files COMPLETED: {} - {} lists written, {} failed, {} skipped, in {} ms", run.where(),
                    run.written, run.failed, run.skipped, System.currentTimeMillis() - run.start);
        } catch (StopAll stop) {
            // every list is concerned: the lists not filtered yet are dropped, job left below 100 % (KO), no end date.
            // Lists already written stay: if the other file type is also ready, their file is still built.
            quietly("drop the lists not filtered by job " + run.jobId, () -> {
                if (run.jobId != null) {
                    sixFilteredStore.dropPendingRowsOfJob(run.jobId);
                }
            });
            throw stop.error;
        } finally {
            run.progress.stopKeepAlive();
        }
    }

    // ------------------------------------------------------------------------------------------ 1. start

    private void prepare(Run run, BatchExport batchExport) {
        try {
            run.version = batchExport.getExportParams().getVersionOpt()
                    .orElseThrow(() -> new IllegalStateException("raw SIX version not found in the export parameters"));
            run.listRaw = run.version.getList();
            run.kind = SixFileKind.of(run.listRaw.getImportFileType()).orElseThrow(() -> new IllegalStateException(
                    "list " + run.listRaw.getId() + " (" + run.listRaw.getImportFileType() + ") is not a SIX raw list"));
            run.delivery = sixExportRunGuard.deliveryOf(run.version.getId());
            run.reason = sixExportRunGuard.generationReasonOf(run.jobId);
            removeRowsOfOlderVersions(run);
            run.progress.add(SixExportProgress.START_PERCENT);

            Optional<SixFilteredPoller> stop = sixExportRunGuard.deliveryStop(run.version.getId(), run.jobId);
            if (stop.isPresent()) {
                stoppedByOther(run, stop.get(), null);
            }

            // output lists, in the order of the export
            List<String> refs = batchExport.getExportParams().getGeneratedFileIds().stream().map(String::valueOf).collect(Collectors.toList());
            run.lists = reglissListRepository.findByListRef(refs).stream()
                    .sorted(Comparator.comparingInt(l -> refs.indexOf(l.getReference())))
                    .collect(Collectors.toList());
            if (run.lists.isEmpty()) {
                throw new IllegalStateException("No active output list for the references " + refs);
            }
            if (run.lists.size() < refs.size()) {
                log.warn("{}: lists not active or deleted, not exported: {}", run.where(), refs.stream()
                        .filter(r -> run.lists.stream().noneMatch(l -> r.equals(l.getReference()))).collect(Collectors.toList()));
            }
            run.listShare = SixExportProgress.listShare(run.lists.size());

            // one snapshot of every filter for the whole run (a change in the UI during the export is not half applied)
            for (ReglissList list : run.lists) {
                List<SixFilters> filters = sixFiltersRepository.getFiltersByListIdAndActiveTrueAndDeletedFalse(list.getId());
                filters.forEach(f -> f.getActiveSixSubfilters().forEach(SixSubFilters::getActiveSixSubORfilters));   // load now
                run.filtersByRef.put(list.getReference(), filters);
            }
            List<SixFilters> cmicSixFilters = sixFiltersRepository.getFiltersByListRefAndActiveTrueAndDeletedFalse(sixCMICList);
            List<SixFilters> e014071SixFilters = sixFiltersRepository.getFiltersByListRefAndActiveTrueAndDeletedFalse(sixE014071List);
            run.exclusions = sixFileFilterService.prepareExclusions(run.kind, run.listRaw.getId(), run.version.getId(),
                    cmicSixFilters, e014071SixFilters, run.filtersByRef.values());

            // one PENDING row per list: the state of every list of this job is visible in SIX_FILTERED_POLLER
            List<SixFilteredPoller> pending = new ArrayList<>();
            for (ReglissList list : run.lists) {
                SixFilteredPoller row = new SixFilteredPoller(run.kind.getPollerFileType(), SixFilteredStore.PENDING, LocalDateTime.now(),
                        run.listRaw.getId(), run.version.getId(), list.getReference(), run.jobId);
                row.setGenerationReason(run.reason);
                pending.add(row);
            }
            for (SixFilteredPoller saved : sixFilteredPollerRepository.saveAll(pending)) {
                run.pendingRowByRef.put(saved.getSixListReference(), saved.getId());
            }
            log.info("{}: {} lists to filter {}", run.where(), run.lists.size(), refs);
        } catch (StopAll stop) {
            throw stop;
        } catch (RuntimeException e) {
            failAll(run, new SixExportException(SixExportException.Stage.PREPARE, run.where(), null, run.fileType(), run.jobId, e));
        }
    }

    // ------------------------------------------------------------------------------------------ 2. one list

    private void processList(Run run, ReglissList list) {
        String ref = list.getReference();
        Optional<SixFilteredPoller> stop = check(run, () -> sixExportRunGuard.listStop(run.version.getId(), run.jobId, ref));
        if (stop.isPresent()) {
            if (SixExportRunGuard.FAILED_ALL.equals(stop.get().getStatus())) {
                stoppedByOther(run, stop.get(), ref);
            }
            // this list failed on the other server: no use filtering it here (its file can never be built)
            log.error("SIX export {}: list {} SKIPPED because {}", run.where(), ref, sixExportRunGuard.describe(stop.get()));
            run.skipped++;
            quietly("drop list " + ref, () -> sixFilteredStore.dropPending(run.pendingRowByRef.get(ref)));
            return;
        }

        long t0 = System.currentTimeMillis();
        try {
            stage(run, ref, SixExportException.Stage.FILTER, () -> {
                waitForAutomaticExport(run, ref);
                return null;
            });
            List<?> rows = stage(run, ref, SixExportException.Stage.FILTER,
                    () -> sixFileFilterService.filter(run.kind, run.filtersByRef.get(ref), run.exclusions));
            run.progress.add(run.listShare * SixExportProgress.FILTER_PART);
            long t1 = System.currentTimeMillis();

            run.rowsWrittenFor = ref;
            int deleted = stage(run, ref, SixExportException.Stage.DELETE_PREVIOUS,
                    () -> sixFilteredStore.deleteVersionOfList(run.kind, run.version.getId(), ref));
            run.progress.add(run.listShare * SixExportProgress.DELETE_PART);
            long t2 = System.currentTimeMillis();

            int written = stage(run, ref, SixExportException.Stage.WRITE, () -> sixFilteredRowWriter.write(run.kind, rows, ref,
                    run.listRaw.getId(), run.version.getId(), run.progress, run.listShare * SixExportProgress.WRITE_PART));
            long t3 = System.currentTimeMillis();

            stage(run, ref, SixExportException.Stage.ANNOUNCE, () -> {
                sixFilteredStore.announce(run.pendingRowByRef.get(ref));
                return null;
            });
            run.rowsWrittenFor = null;
            run.written++;
            log.info("SIX export {} list {}: {} rows written, {} old rows deleted - filter {} ms, delete {} ms, write {} ms",
                    run.where(), ref, written, deleted, t1 - t0, t2 - t1, t3 - t2);
        } catch (SixExportException e) {
            if (e.stopsAllLists()) {
                failAll(run, e);
            }
            failList(run, list, e);
        }
    }

    /**
     * Export phase 1, option (a): a REGENERATION does not touch a list while the automatic export (GENERATION) of the
     * same list and raw version is still working on it (rows PENDING / READY / BUILDING): both use the same
     * FILTERED_SIX_* rows (VERSION_ID + SIX_LIST_REF), and the regeneration starts by deleting them. It waits (checked
     * every minute, its job kept alive by the keep-alive timer), then continues: both exports are done, one after the
     * other. A generation whose job stopped (server crash ...) is not waited for.
     */
    private void waitForAutomaticExport(Run run, String ref) {
        if (!SixExportRunGuard.REGENERATION.equals(run.reason)) {
            return;
        }
        boolean waiting = false;
        while (automaticExportWorksOn(run, ref)) {
            if (!waiting) {
                log.info("SIX export {}: list {} waits for the automatic export of the same version to finish", run.where(), ref);
                waiting = true;
            }
            try {
                Thread.sleep(waitForAutomaticExportMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for the automatic export of list " + ref, e);
            }
        }
        if (waiting) {
            log.info("SIX export {}: list {} - automatic export finished, regeneration continues", run.where(), ref);
        }
    }

    private boolean automaticExportWorksOn(Run run, String ref) {
        LocalDateTime buildAliveSince = LocalDateTime.now().minusMinutes(SixExportRunGuard.KO_AFTER_MINUTES);
        return sixFilteredStore.openRowsOfList(ref, run.version.getId()).stream()
                .filter(r -> SixExportRunGuard.GENERATION.equals(r.getGenerationReason()))
                .anyMatch(r -> SixFilteredStore.BUILDING.equals(r.getStatus())
                        ? r.getUpdatedTime() != null && r.getUpdatedTime().isAfter(buildAliveSince)
                        : sixExportRunGuard.isJobWorking(r.getBatchJobExecutionId()));
    }

    // ------------------------------------------------------------------------------------------ errors

    /** One list failed: rows removed, list marked FAILED (the other server skips / drops it), mail; the run continues. */
    private void failList(Run run, ReglissList list, SixExportException error) {
        log.error(error.getMessage(), error);
        run.failed++;
        String ref = list.getReference();
        quietly("record the failure of list " + ref,
                () -> sixExportRunGuard.listFailed(run.kind.getPollerFileType(), run.listRaw.getId(), run.version.getId(), ref, run.jobId));
        quietly("drop list " + ref, () -> sixFilteredStore.dropPending(run.pendingRowByRef.get(ref)));
        removePartialRows(run);
        quietly("send the alert mail", () -> emailService.sendEmailDjImportGeneralError(list, error.mailText()));
    }

    /** The error concerns every list: FAILED_ALL (the other server stops before its next list), mail, stop this run. */
    private void failAll(Run run, SixExportException error) {
        log.error(error.getMessage(), error);
        if (run.version != null && run.listRaw != null) {
            quietly("record the failure of the delivery", () -> sixExportRunGuard.deliveryFailed(
                    run.kind == null ? null : run.kind.getPollerFileType(), run.listRaw.getId(),
                    run.version.getId(), error.getListRef(), run.jobId));
        }
        removePartialRows(run);
        ReglissList mailList = run.lists.stream().filter(l -> l.getReference().equals(error.getListRef())).findFirst()
                .orElse(run.listRaw);
        if (mailList != null) {
            quietly("send the alert mail", () -> emailService.sendEmailDjImportGeneralError(mailList, error.mailText()));
        }
        throw new StopAll(error);
    }

    /** The other server stopped the delivery: stop here too, without a second mail. */
    private void stoppedByOther(Run run, SixFilteredPoller failure, String ref) {
        String text = "SIX export STOPPED before " + (ref == null ? "the first list" : "list " + ref) + " - " + run.where()
                + " - because " + sixExportRunGuard.describe(failure);
        log.error(text);
        throw new StopAll(new SixExportException(SixExportException.Stage.PREPARE, run.where(), ref, run.fileType(), run.jobId,
                new IllegalStateException(text)));
    }

    private void removePartialRows(Run run) {
        String ref = run.rowsWrittenFor;
        if (ref != null && run.kind != null && run.version != null) {
            quietly("remove the partial rows of list " + ref, () -> sixFilteredStore.deleteVersionOfList(run.kind, run.version.getId(), ref));
        }
        run.rowsWrittenFor = null;
    }

    /** Runs one stage of a list; any error becomes a SixExportException that says where it stopped. */
    private <T> T stage(Run run, String ref, SixExportException.Stage stage, Supplier<T> work) {
        try {
            return work.get();
        } catch (SixExportException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SixExportException(stage, run.where(), ref, run.fileType(), run.jobId, e);
        }
    }

    /** Reading the shared failure signals: a database error here concerns every list. */
    private <T> T check(Run run, Supplier<T> work) {
        try {
            return work.get();
        } catch (RuntimeException e) {
            failAll(run, new SixExportException(SixExportException.Stage.PREPARE, run.where(), null, run.fileType(), run.jobId, e));
            return null;   // not reached
        }
    }

    /**
     * Part 4: the automatic export (not a regeneration) removes the SIX_FILTERED_POLLER rows of its file type left by
     * older raw versions (finished, or of a job that stopped). An error here does not stop the export.
     */
    private void removeRowsOfOlderVersions(Run run) {
        if (!SixExportRunGuard.GENERATION.equals(run.reason)) {
            return;
        }
        quietly("remove the SIX_FILTERED_POLLER rows of older versions", () -> {
            int removed = sixFilteredStore.deleteOlderVersionRows(run.kind.getPollerFileType(), run.version.getId(),
                    sixExportRunGuard::isJobWorking);
            if (removed > 0) {
                log.info("{}: {} SIX_FILTERED_POLLER row(s) of older {} versions removed", run.where(), removed,
                        run.kind.getPollerFileType());
            }
        });
    }

    private static void quietly(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.error("SIX export: could not {}: {}", what, SixExportException.rootCause(e), e);
        }
    }

    /** Stops the whole run (the error is already logged and mailed). */
    private static final class StopAll extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient SixExportException error;

        StopAll(SixExportException error) {
            super(error.getMessage(), error, false, false);
            this.error = error;
        }
    }

    /** State of one export run (one raw version). */
    private static final class Run {
        final Long jobId;
        final SixExportProgress progress;
        final long start = System.currentTimeMillis();
        Version version;
        ReglissList listRaw;
        SixFileKind kind;
        String delivery;
        /** GENERATION or REGENERATION (SixExportRunGuard). */
        String reason;
        double listShare;
        SixFileFilterService.Exclusions exclusions;
        List<ReglissList> lists = new ArrayList<>();
        final Map<String, List<SixFilters>> filtersByRef = new LinkedHashMap<>();
        final Map<String, Long> pendingRowByRef = new LinkedHashMap<>();
        String rowsWrittenFor;
        int written;
        int failed;
        int skipped;

        Run(Long jobId, SixExportProgress progress) {
            this.jobId = jobId;
            this.progress = progress;
        }

        String fileType() {
            return kind == null ? null : kind.name().toLowerCase(Locale.ROOT);
        }

        String where() {
            return (kind == null ? "SIX" : kind.name()) + " file version " + (version == null ? "?" : version.getId())
                    + (delivery == null ? "" : ", delivery " + delivery) + ", job " + jobId;
        }
    }
}
