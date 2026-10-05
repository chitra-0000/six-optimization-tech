package com.bnpp.regliss.importer.six.service;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

// TODO: re-add project import for: BatchProgressService

/**
 * Progress of one SIX export job (BATCH_JOB_EXECUTION.PERCENT, existing columns only).
 *
 * Plan of one job (one raw file, N output lists), the same for 1 or 2 servers, export or regeneration:
 *    5 %                      start (lists, filters, CMIC / E014071 exclusions)
 *   90 % / N per list         filter 30 % - delete previous rows 5 % - write 45 % (per batch) - XML file 20 %
 *   ---- automatic total: at most 95 % ----
 *   100 % + end date          ONLY by the existing BatchManagementService.updateBatchExecutionCompletedProgress
 *                             (+ markBatchExecutionFinishedBySys) when every list of the job has its file.
 * A job with a failed list never gets 100 % / end date: it stays at its percentage and becomes KO in the UI
 * 30 minutes after its last update.
 *
 * Whole percents only (incrementExportBatchForSix takes an int): fractions are added up and sent when they reach 1,
 * and the total sent by one instance never passes {@link #AUTOMATIC_MAX}.
 *
 * Keep-alive: {@link #startKeepAlive()} refreshes LAST_UPDATE_DATE every minute (increment 0) while the export
 * works, also during one long SQL statement; {@link #stopKeepAlive()} stops it at the end of the work.
 *
 * Not a Spring bean: one instance per export run. Thread safe (writer threads report their batches).
 */
@Slf4j
public final class SixExportProgress {

    public static final double START_PERCENT = 5.0;
    public static final double LISTS_PERCENT = 90.0;
    public static final double AUTOMATIC_MAX = START_PERCENT + LISTS_PERCENT;
    /** Parts of one list's share. */
    public static final double FILTER_PART = 0.30;
    public static final double DELETE_PART = 0.05;
    public static final double WRITE_PART = 0.45;
    public static final double XML_PART = 0.20;

    private static final long KEEP_ALIVE_SECONDS = 60L;

    private final BatchProgressService batchProgressService;
    private final Long batchJobExecutionId;
    private double pending;
    private int sent;
    private ScheduledExecutorService keepAlive;

    public SixExportProgress(BatchProgressService batchProgressService, Long batchJobExecutionId) {
        this.batchProgressService = batchProgressService;
        this.batchJobExecutionId = batchJobExecutionId;
    }

    /** Share of one list when the job has {@code lists} output lists. */
    public static double listShare(int lists) {
        return lists <= 0 ? 0 : LISTS_PERCENT / lists;
    }

    /** Adds a (possibly fractional) percent; whole percents are written, the rest is kept for the next call. */
    public synchronized void add(double percent) {
        if (percent > 0) {
            pending += percent;
        }
        int whole = Math.min((int) pending, (int) AUTOMATIC_MAX - sent);
        if (whole > 0) {
            pending -= whole;
            sent += whole;
            push(whole);
        }
    }

    /** Refreshes LAST_UPDATE_DATE every minute until {@link #stopKeepAlive()}. */
    public synchronized void startKeepAlive() {
        if (keepAlive != null || batchJobExecutionId == null) {
            return;
        }
        keepAlive = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "six-export-keepalive-" + batchJobExecutionId);
            t.setDaemon(true);
            return t;
        });
        keepAlive.scheduleAtFixedRate(() -> push(0), KEEP_ALIVE_SECONDS, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS);
    }

    public synchronized void stopKeepAlive() {
        if (keepAlive != null) {
            keepAlive.shutdownNow();
            keepAlive = null;
        }
    }

    private void push(int percent) {
        if (batchJobExecutionId == null) {
            return;
        }
        try {
            batchProgressService.incrementExportBatchForSix(batchJobExecutionId, percent);
        } catch (RuntimeException e) {
            // progress is information only: never stop the export for it
            log.warn("Could not update the progress of job {}: {}", batchJobExecutionId, e.getMessage());
        }
    }
}
