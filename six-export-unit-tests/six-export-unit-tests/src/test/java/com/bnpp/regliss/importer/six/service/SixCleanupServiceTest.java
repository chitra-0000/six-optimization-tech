package com.bnpp.regliss.importer.six.service;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.IOException;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// TODO: re-add project imports for: BatchJobExecution, BatchJobExecutionRepository, BatchJobType, BatchExportRepository,
// VersionRepository, SixXmlGenerationService, SixHeldFileFixtures

@RunWith(MockitoJUnitRunner.Silent.class)
public class SixCleanupServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.now();

    @InjectMocks
    private SixCleanupService service;

    @Mock
    private SixCleanupStore sixCleanupStore;
    @Mock
    private SixFilteredStore sixFilteredStore;
    @Mock
    private SixExportRunGuard sixExportRunGuard;
    @Mock
    private SixXmlGenerationService sixXmlGenerationService;
    @Mock
    private BatchJobExecutionRepository batchJobExecutionRepository;
    @Mock
    private BatchExportRepository batchExportRepository;
    @Mock
    private VersionRepository versionRepository;

    @Before
    public void setUp() throws IOException {
        when(batchJobExecutionRepository.findByJobTypeInAndEndDateIsNull(any())).thenReturn(Collections.emptyList());
        when(sixFilteredStore.rowsInStatus(any())).thenReturn(Collections.emptyList());
        when(sixXmlGenerationService.heldFiles()).thenReturn(Collections.emptyList());
        when(sixCleanupStore.clearFilteredTables(any())).thenAnswer(inv -> {
            ((BooleanSupplier) inv.getArgument(0)).getAsBoolean();          // the idle check passed to the store
            return SixCleanupStore.ClearResult.CLEARED;
        });
        when(sixCleanupStore.hasRowsOfVersion(any(), anyLong())).thenReturn(true);
        when(sixCleanupStore.deleteOlderVersions(any(), anyLong(), any())).thenAnswer(inv -> {
            ((BooleanSupplier) inv.getArgument(2)).getAsBoolean();
            return 3;
        });
    }

    private BatchJobExecution job(long id) {
        BatchJobExecution job = mock(BatchJobExecution.class);
        when(job.getId()).thenReturn(id);
        when(job.getNodeId()).thenReturn("node-" + id);
        return job;
    }

    private static SixFilteredStore.PollerRow row(long id, String fileType, Long version, Long job, String status, LocalDateTime updated) {
        return SixPollerRowFixtures.row(id, fileType, version, job, "951", status, updated);
    }

    private void inProgress(SixFilteredStore.PollerRow... rows) {
        when(sixFilteredStore.rowsInStatus(SixFilteredStore.PENDING, SixFilteredStore.READY, SixFilteredStore.BUILDING,
                SixFilteredStore.BUILT)).thenReturn(Arrays.asList(rows));
    }

    // ------------------------------------------------------------------ both parts run

    @Test
    public void idle_bothPartsRun() throws IOException {
        when(versionRepository.getLatestVersionIdOfInstruments()).thenReturn(10L);
        when(versionRepository.getLatestVersionIdOfStructure()).thenReturn(20L);
        when(versionRepository.getLatestVersionIdOfOptions()).thenReturn(null);          // no options version
        when(sixXmlGenerationService.removeTempFilesAndEmptyFolders(SixCleanupService.TMP_FILE_AGE_MINUTES)).thenReturn(2);

        SixCleanupService.Result result = service.runCleanup();

        assertNull(result.getExportSkippedBecause());
        assertTrue(result.isExportTablesCleared());
        assertNull(result.getImportSkippedBecause());
        assertEquals(Integer.valueOf(3), result.getRawRowsDeleted().get("SIX_INSTRUMENTS"));
        assertEquals(Integer.valueOf(3), result.getRawRowsDeleted().get("SIX_STRUCTURED"));
        assertFalse(result.getRawRowsDeleted().containsKey("SIX_OPTION"));
        verify(sixCleanupStore).deleteOlderVersions(eq(SixCleanupStore.RawTable.INSTRUMENTS), eq(10L), any());
        assertEquals("held files delivered [], removed (DJ already has newer) [], not moved [], filtered tables cleared true;"
                + " raw rows deleted {SIX_INSTRUMENTS=3, SIX_STRUCTURED=3}", result.toString());
    }

    // ------------------------------------------------------------------ export part busy

    @Test
    public void exportJobWorking_exportSkipped_importJobStopped_importRuns() {
        BatchJobExecution exportJob = job(1L);
        BatchJobExecution deadImportJob = job(2L);
        when(batchJobExecutionRepository.findByJobTypeInAndEndDateIsNull(Collections.singletonList(BatchJobType.BATCH_EXPORT_SIXRAW)))
                .thenReturn(Collections.singletonList(exportJob));
        when(batchJobExecutionRepository.findByJobTypeInAndEndDateIsNull(Collections.singletonList(BatchJobType.BATCH_IMPORT_SIXRAW)))
                .thenReturn(Collections.singletonList(deadImportJob));
        when(sixExportRunGuard.isJobWorking(1L)).thenReturn(true);
        when(sixExportRunGuard.isJobWorking(2L)).thenReturn(false);          // server stopped: not waited for

        SixCleanupService.Result result = service.runCleanup();

        assertEquals("SIX job 1 is running", result.getExportSkippedBecause());
        assertFalse(result.isExportTablesCleared());
        assertNull(result.getImportSkippedBecause());
        assertTrue(result.toString().startsWith("export part skipped: SIX job 1 is running; raw rows deleted"));
    }

    @Test
    public void exportRequestedNotStarted_skipped_confidenceWaiting_importSkipped() {
        when(batchExportRepository.countSixExportsNotStarted()).thenReturn(1L);
        when(batchExportRepository.countSixConfidenceEntries()).thenReturn(1L);

        SixCleanupService.Result result = service.runCleanup();

        assertEquals("a SIX export is requested and not yet started", result.getExportSkippedBecause());
        assertEquals("a SIX delivery waits for its confidence step (SIX_CONFIDENCE_VALUES)", result.getImportSkippedBecause());
        assertTrue(result.toString().endsWith("; import part skipped: a SIX delivery waits for its confidence step (SIX_CONFIDENCE_VALUES)"));
    }

    @Test
    public void importJobWorking_importSkipped() {
        BatchJobExecution importJob = job(3L);
        when(batchJobExecutionRepository.findByJobTypeInAndEndDateIsNull(Collections.singletonList(BatchJobType.BATCH_IMPORT_SIXRAW)))
                .thenReturn(Collections.singletonList(importJob));
        when(sixExportRunGuard.isJobWorking(3L)).thenReturn(true);
        assertEquals("SIX job 3 is running", service.runCleanup().getImportSkippedBecause());
    }

    @Test
    public void listRowsInProgress_byStatus() {
        SixFilteredStore.PollerRow staleBuilding = row(1, "INSTR", 5L, 7L, SixFilteredStore.BUILDING, NOW.minusHours(2));
        SixFilteredStore.PollerRow buildingNoTime = row(2, "INSTR", 5L, 7L, SixFilteredStore.BUILDING, null);
        SixFilteredStore.PollerRow pendingNoJob = row(3, "INSTR", 5L, null, SixFilteredStore.PENDING, NOW);
        SixFilteredStore.PollerRow readyDeadJob = row(4, "INSTR", 5L, 8L, SixFilteredStore.READY, NOW);
        SixFilteredStore.PollerRow pendingDeadJobAgain = row(5, "INSTR", 5L, 8L, SixFilteredStore.PENDING, NOW);
        SixFilteredStore.PollerRow freshBuilding = row(6, "INSTR", 5L, 7L, SixFilteredStore.BUILDING, NOW);
        inProgress(staleBuilding, buildingNoTime, pendingNoJob, readyDeadJob, pendingDeadJobAgain, freshBuilding);

        assertEquals(Optional.of("SIX export list in progress: " + freshBuilding), service.whyExportBusy(false));
        verify(sixExportRunGuard).isJobWorking(8L);                    // asked once per job

        inProgress(row(7, "STRUCT", 6L, 9L, SixFilteredStore.READY, NOW));
        when(sixExportRunGuard.isJobWorking(9L)).thenReturn(true);
        assertTrue(service.whyExportBusy(false).isPresent());

        inProgress(row(8, "STRUCT", 6L, null, SixFilteredStore.BUILT, null));
        assertTrue(service.whyExportBusy(false).isPresent());

        inProgress(staleBuilding);
        assertFalse(service.whyExportBusy(false).isPresent());
    }

    // ------------------------------------------------------------------ export part steps

    @Test
    public void exportStartsDuringTheHoldingFolderStep_tablesKept() {
        when(batchExportRepository.countSixExportsNotStarted()).thenReturn(0L, 1L);
        SixCleanupService.Result result = service.runCleanup();
        assertEquals("a SIX job started during the cleanup", result.getExportSkippedBecause());
        verify(sixCleanupStore, never()).clearFilteredTables(any());
    }

    @Test
    public void tablesBusyUnderTheLock() {
        doReturn(SixCleanupStore.ClearResult.BUSY).when(sixCleanupStore).clearFilteredTables(any());
        assertEquals("a SIX job started during the cleanup", service.runCleanup().getExportSkippedBecause());
    }

    @Test
    public void holdingFolderNotReadable_exportPartStopped() throws IOException {
        when(sixXmlGenerationService.heldFiles()).thenThrow(new IOException("denied"));
        SixCleanupService.Result result = service.runCleanup();
        assertEquals("error: IOException: denied", result.getExportSkippedBecause());
        assertNull(result.getImportSkippedBecause());
    }

    @Test
    public void tmpFilesNotRemoved_onlyLogged() throws IOException {
        when(sixXmlGenerationService.removeTempFilesAndEmptyFolders(anyLong())).thenThrow(new IOException("busy"));
        assertTrue(service.runCleanup().isExportTablesCleared());
    }

    /**
     * List 951: two held files, nothing delivered yet -> newest to DJ IN, older removed.
     * List 235: newest held file is older than what DJ received -> removed.
     * List 952: delivery fails (kept, "not moved"); its older file cannot be removed either.
     */
    @Test
    public void heldFiles_deliveredOrRemoved() throws IOException {
        SixXmlGenerationService.HeldFile a1 = held("20261003101500_GENERATION", "951", NOW.minusHours(5));
        SixXmlGenerationService.HeldFile a2 = held("20261004101500_REGENERATION", "951", NOW.minusHours(4));
        SixXmlGenerationService.HeldFile b1 = held("20261002101500_GENERATION", "235", NOW.minusHours(3));
        SixXmlGenerationService.HeldFile c1 = held("unknown", "952", NOW.minusHours(9));
        SixXmlGenerationService.HeldFile c2 = held("20261004101500_GENERATION", "952", NOW.minusHours(1));
        when(sixXmlGenerationService.heldFiles()).thenReturn(Arrays.asList(a2, b1, a1, c1, c2));
        doThrow(new IllegalStateException("locked")).when(sixXmlGenerationService).discardHeldFile(c1);
        when(sixXmlGenerationService.deliverHeldFile(c2)).thenThrow(new IOException("disk full"));

        // DJ received delivery 20261003101500 of list 235 (two DONE rows: the newest counts); other rows ignored
        List<SixFilteredStore.PollerRow> done = Arrays.asList(
                SixPollerRowFixtures.row(2, "INSTR", 31L, 1L, "235", SixFilteredStore.DONE, NOW.minusDays(2)),
                SixPollerRowFixtures.row(1, "INSTR", 30L, 1L, "235", SixFilteredStore.DONE, NOW.minusDays(1)),
                SixPollerRowFixtures.row(6, "INSTR", 34L, 1L, "953", SixFilteredStore.DONE, NOW.minusDays(1)),
                SixPollerRowFixtures.row(3, "INSTR", 32L, 1L, "235", SixFilteredStore.DONE, NOW.minusDays(3)),
                SixPollerRowFixtures.row(4, "INSTR", 33L, 1L, null, SixFilteredStore.DONE, NOW),
                SixPollerRowFixtures.row(5, "INSTR", null, 1L, "951", SixFilteredStore.DONE, NOW));
        when(sixFilteredStore.rowsInStatus(SixFilteredStore.DONE)).thenReturn(done);
        // list 953: DJ received an OLDER delivery (unknown key) -> the held file is newer and is delivered
        SixXmlGenerationService.HeldFile d1 = held("20261004101500_GENERATION", "953", NOW.minusHours(2));
        List<SixXmlGenerationService.HeldFile> all = new ArrayList<>(Arrays.asList(a2, b1, a1, c1, c2, d1));
        when(sixXmlGenerationService.heldFiles()).thenReturn(all);
        when(sixExportRunGuard.deliveryOf(30L)).thenReturn("20261003101500");
        when(sixExportRunGuard.deliveryOf(31L)).thenReturn("20261003101500");
        when(sixExportRunGuard.deliveryOf(32L)).thenReturn("version:32");

        SixCleanupService.Result result = service.runCleanup();

        assertEquals(Arrays.asList(a2.toString(), d1.toString()), result.getDelivered());
        assertEquals(Arrays.asList(a1.toString(), b1.toString()), result.getDiscarded());
        assertEquals(Arrays.asList(c1.toString(), c2.toString()), result.getNotMoved());
        verify(sixXmlGenerationService).deliverHeldFile(a2);
        verify(sixXmlGenerationService).discardHeldFile(a1);
        verify(sixXmlGenerationService).discardHeldFile(b1);
    }

    private static SixXmlGenerationService.HeldFile held(String group, String list, LocalDateTime modified) {
        return SixHeldFileFixtures.held(group, list, Paths.get("/hold", group, list, "CONVERTER-" + list + ".xml"), modified);
    }

    // ------------------------------------------------------------------ import part steps

    @Test
    public void rawVersions_keptWhileUsedByAnExport_orWithoutRows() {
        when(versionRepository.getLatestVersionIdOfInstruments()).thenReturn(10L);
        when(versionRepository.getLatestVersionIdOfStructure()).thenReturn(20L);
        when(versionRepository.getLatestVersionIdOfOptions()).thenReturn(30L);
        when(sixCleanupStore.hasRowsOfVersion(SixCleanupStore.RawTable.OPTIONS, 30L)).thenReturn(false);
        when(sixExportRunGuard.isJobWorking(7L)).thenReturn(true);
        inProgress(row(1, "INSTR", 8L, 7L, SixFilteredStore.READY, NOW),     // INSTR version 8 still read
                row(2, "INSTR", 9L, 7L, SixFilteredStore.READY, NOW),
                row(3, "INSTR", null, 7L, SixFilteredStore.READY, NOW),
                row(4, "STRUCT", 25L, 7L, SixFilteredStore.READY, NOW));     // newer than latest: latest kept
        when(batchExportRepository.countSixExportsNotStarted()).thenReturn(1L);      // export part skipped

        SixCleanupService.Result result = service.runCleanup();

        verify(sixCleanupStore).deleteOlderVersions(eq(SixCleanupStore.RawTable.INSTRUMENTS), eq(8L), any());
        verify(sixCleanupStore).deleteOlderVersions(eq(SixCleanupStore.RawTable.STRUCTURED), eq(20L), any());
        verify(sixCleanupStore, never()).deleteOlderVersions(eq(SixCleanupStore.RawTable.OPTIONS), anyLong(), any());
        assertEquals(2, result.getRawRowsDeleted().size());
    }

    @Test
    public void importStartsDuringTheDelete_storeAsksAgain() {
        when(versionRepository.getLatestVersionIdOfInstruments()).thenReturn(10L);
        when(batchExportRepository.countSixConfidenceEntries()).thenReturn(0L, 1L);
        doAnswer(inv -> ((BooleanSupplier) inv.getArgument(2)).getAsBoolean() ? 5 : 0)
                .when(sixCleanupStore).deleteOlderVersions(any(), anyLong(), any());
        assertEquals(Integer.valueOf(0), service.runCleanup().getRawRowsDeleted().get("SIX_INSTRUMENTS"));
    }

    @Test
    public void importError_importPartStopped() {
        when(versionRepository.getLatestVersionIdOfInstruments()).thenThrow(new IllegalStateException("ORA-1"));
        assertEquals("error: IllegalStateException: ORA-1", service.runCleanup().getImportSkippedBecause());
    }

    // ------------------------------------------------------------------ helpers

    @Test
    public void deliveryKeyOfGroup() {
        assertEquals(Long.valueOf(20261004101500L), SixCleanupService.deliveryKeyOfGroup("20261004101500_GENERATION"));
        assertEquals(Long.valueOf(20261004101500L), SixCleanupService.deliveryKeyOfGroup("20261004101500_REGENERATION"));
        assertEquals(Long.valueOf(42L), SixCleanupService.deliveryKeyOfGroup("42"));
        assertNull(SixCleanupService.deliveryKeyOfGroup("x_GENERATION"));
        assertNull(SixCleanupService.deliveryKeyOfGroup("_GENERATION"));
        assertNull(SixCleanupService.deliveryKeyOfGroup("1234567890123456789"));     // more than 18 digits
    }

    @Test
    public void stamp_orderEqualsHashCode() {
        SixCleanupService.Stamp unknown = new SixCleanupService.Stamp(null, null);
        SixCleanupService.Stamp early = new SixCleanupService.Stamp(1L, NOW);
        SixCleanupService.Stamp sameEarly = new SixCleanupService.Stamp(1L, NOW);
        SixCleanupService.Stamp later = new SixCleanupService.Stamp(1L, NOW.plusSeconds(1));
        SixCleanupService.Stamp nextDelivery = new SixCleanupService.Stamp(2L, null);
        List<SixCleanupService.Stamp> stamps = new ArrayList<>(Arrays.asList(nextDelivery, later, early, unknown));
        Collections.sort(stamps);
        assertEquals(Arrays.asList(unknown, early, later, nextDelivery), stamps);
        assertEquals(early, sameEarly);
        assertEquals(early.hashCode(), sameEarly.hashCode());
        assertNotEquals(early, later);
        assertNotEquals(early, "delivery 1");
        assertEquals("delivery 1 at " + NOW, early.toString());
    }
}
