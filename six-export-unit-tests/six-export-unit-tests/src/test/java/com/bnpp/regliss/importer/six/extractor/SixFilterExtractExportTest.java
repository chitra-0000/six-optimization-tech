package com.bnpp.regliss.importer.six.extractor;

import com.bnpp.regliss.importer.six.service.SixPollerRowFixtures;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// TODO: re-add project imports for: SixFiltersRepository, ReglissListRepository, SixFileFilterService, SixFilteredPollerRepository,
// SixFilteredStore, SixFilteredRowWriter, SixExportRunGuard, SixExportException, SixFileKind, BatchProgressService, EmailService,
// BatchExport, BatchExportParams, Version, ReglissList, ImportFileType, SixFilters, SixSubFilters, SixFilteredPoller,
// SixPollerRowFixtures

@RunWith(MockitoJUnitRunner.Silent.class)
public class SixFilterExtractExportTest {

    private static final LocalDateTime NOW = LocalDateTime.now();

    @InjectMocks
    private SixFilterExtractExport export;

    @Mock
    private SixFiltersRepository sixFiltersRepository;
    @Mock
    private ReglissListRepository reglissListRepository;
    @Mock
    private SixFileFilterService sixFileFilterService;
    @Mock
    private SixFilteredPollerRepository sixFilteredPollerRepository;
    @Mock
    private SixFilteredStore sixFilteredStore;
    @Mock
    private SixFilteredRowWriter sixFilteredRowWriter;
    @Mock
    private SixExportRunGuard sixExportRunGuard;
    @Mock
    private BatchProgressService batchProgressService;
    @Mock
    private EmailService emailService;

    private BatchExport batchExport;
    private BatchExportParams params;
    private Version version;
    private ReglissList raw;
    private ReglissList list951;
    private ReglissList list235;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() {
        ReflectionTestUtils.setField(export, "waitForAutomaticExportMillis", 0L);
        ReflectionTestUtils.setField(export, "sixCMICList", "CMIC");
        ReflectionTestUtils.setField(export, "sixE014071List", "E014071");
        raw = list(10L, "RAW");
        when(raw.getImportFileType()).thenReturn(ImportFileType.SIX_INSTRUMENTS_FILE);
        version = mock(Version.class);
        when(version.getId()).thenReturn(100L);
        when(version.getList()).thenReturn(raw);
        params = mock(BatchExportParams.class);
        when(params.getVersionOpt()).thenReturn(Optional.of(version));
        when(params.getGeneratedFileIds()).thenReturn(Arrays.asList(951L, 235L, 999L));
        batchExport = mock(BatchExport.class);
        when(batchExport.getExportParams()).thenReturn(params);
        when(batchExport.getBatchExecutionId()).thenReturn(7L);

        list951 = list(1L, "951");
        list235 = list(2L, "235");
        List<ReglissList> found = Arrays.asList(list235, list951);
        when(reglissListRepository.findByListRef(Arrays.asList("951", "235", "999"))).thenReturn(found);
        SixSubFilters sub = mock(SixSubFilters.class);
        SixFilters filter = mock(SixFilters.class);
        when(filter.getActiveSixSubfilters()).thenReturn(new LinkedHashSet<>(Collections.singletonList(sub)));
        when(sixFiltersRepository.getFiltersByListIdAndActiveTrueAndDeletedFalse(anyLong())).thenReturn(Collections.singletonList(filter));
        when(sixFilteredPollerRepository.saveAll(anyList())).thenAnswer(inv -> {
            List<SixFilteredPoller> rows = new ArrayList<>((List<SixFilteredPoller>) inv.getArgument(0));
            for (int i = 0; i < rows.size(); i++) {
                rows.get(i).setId(500L + i);
            }
            return rows;
        });

        when(sixExportRunGuard.deliveryOf(100L)).thenReturn("D1");
        when(sixExportRunGuard.generationReasonOf(7L)).thenReturn(SixExportRunGuard.GENERATION);
        when(sixExportRunGuard.deliveryStop(anyLong(), any())).thenReturn(Optional.empty());
        when(sixExportRunGuard.listStop(anyLong(), any(), anyString())).thenReturn(Optional.empty());
        when(sixExportRunGuard.describe(any())).thenReturn("delivery failed");
        when(sixFileFilterService.filter(eq(SixFileKind.INSTRUMENT), anyList(), any())).thenReturn(Collections.singletonList("row"));
        when(sixFilteredRowWriter.write(any(), anyList(), anyString(), anyLong(), anyLong(), any(), anyDouble())).thenReturn(1);
        when(sixFilteredStore.deleteOlderVersionRows(anyString(), anyLong(), any())).thenReturn(2);
    }

    @After
    public void clearInterruptFlag() {
        Thread.interrupted();
    }

    private static ReglissList list(Long id, String reference) {
        ReglissList l = mock(ReglissList.class);
        when(l.getId()).thenReturn(id);
        when(l.getReference()).thenReturn(reference);
        return l;
    }

    private SixExportException expectStop() {
        try {
            export.filteringSixRawFiles(batchExport);
            fail();
            return null;
        } catch (SixExportException e) {
            return e;
        }
    }

    // ------------------------------------------------------------------ normal runs

    @Test
    public void generation_everyListFilteredAndWritten() {
        export.filteringSixRawFiles(batchExport);

        ArgumentCaptor<List<SixFilteredPoller>> pending = ArgumentCaptor.forClass(List.class);
        verify(sixFilteredPollerRepository).saveAll(pending.capture());
        assertEquals(Arrays.asList("951", "235"), Arrays.asList(pending.getValue().get(0).getSixListReference(),
                pending.getValue().get(1).getSixListReference()));                    // export order
        SixFilteredPoller first = pending.getValue().get(0);
        assertEquals("INSTR", first.getFileType());
        assertEquals(SixFilteredStore.PENDING, first.getStatus());
        assertEquals(SixExportRunGuard.GENERATION, first.getGenerationReason());
        verify(sixFilteredStore).deleteOlderVersionRows(eq("INSTR"), eq(100L), any());
        verify(sixFileFilterService).prepareExclusions(eq(SixFileKind.INSTRUMENT), eq(10L), eq(100L), any(), any(), any());
        verify(sixFilteredStore).announce(500L);
        verify(sixFilteredStore).announce(501L);
        verify(sixFilteredStore).deleteVersionOfList(SixFileKind.INSTRUMENT, 100L, "951");
        verify(batchProgressService).incrementExportBatchForSix(7L, 5);
        verify(emailService, never()).sendEmailDjImportGeneralError(any(), anyString());
    }

    @Test
    public void generation_noOlderRowRemoved_andCleanupErrorIgnored() {
        when(sixFilteredStore.deleteOlderVersionRows(anyString(), anyLong(), any())).thenReturn(0);
        export.filteringSixRawFiles(batchExport);
        when(sixFilteredStore.deleteOlderVersionRows(anyString(), anyLong(), any())).thenThrow(new IllegalStateException("db"));
        export.filteringSixRawFiles(batchExport);
        verify(sixFilteredStore, times(4)).announce(anyLong());
    }

    /**
     * Regeneration: list 951 waits while the automatic export of the same version is building it (refreshed); then the
     * open rows no longer count (old build, never refreshed, stopped job, regeneration row) and it continues.
     */
    @Test
    public void regeneration_waitsForTheAutomaticExportOfTheSameList() {
        when(sixExportRunGuard.generationReasonOf(7L)).thenReturn(SixExportRunGuard.REGENERATION);
        List<SixFilteredStore.PollerRow> working = Collections.singletonList(
                SixPollerRowFixtures.row(1, "INSTR", 1L, 100L, 3L, "951", SixFilteredStore.BUILDING, NOW, null));
        List<SixFilteredStore.PollerRow> notWorking = Arrays.asList(
                SixPollerRowFixtures.row(2, "INSTR", 1L, 100L, 3L, "951", SixFilteredStore.BUILDING, NOW.minusHours(1), null),
                SixPollerRowFixtures.row(3, "INSTR", 1L, 100L, 3L, "951", SixFilteredStore.BUILDING, null, null),
                SixPollerRowFixtures.row(4, "INSTR", 1L, 100L, 4L, "951", SixFilteredStore.READY, NOW, null),
                SixPollerRowFixtures.row(5, "INSTR", 1L, 100L, 5L, "951", SixFilteredStore.PENDING, NOW, "REGENERATION"));
        when(sixFilteredStore.openRowsOfList("951", 100L)).thenReturn(working, working, notWorking);
        when(sixFilteredStore.openRowsOfList("235", 100L)).thenReturn(Collections.emptyList());

        export.filteringSixRawFiles(batchExport);

        verify(sixFilteredStore, times(3)).openRowsOfList("951", 100L);
        verify(sixExportRunGuard).isJobWorking(4L);
        verify(sixFilteredStore, never()).deleteOlderVersionRows(anyString(), anyLong(), any());   // not for a regeneration
        verify(sixFilteredStore, times(2)).announce(anyLong());
    }

    @Test
    public void regeneration_interruptedWhileWaiting_listFails_runContinues() {
        when(sixExportRunGuard.generationReasonOf(7L)).thenReturn(SixExportRunGuard.REGENERATION);
        List<SixFilteredStore.PollerRow> working = Collections.singletonList(
                SixPollerRowFixtures.row(1, "INSTR", 1L, 100L, 3L, "951", SixFilteredStore.READY, NOW, null));
        when(sixExportRunGuard.isJobWorking(3L)).thenReturn(true);
        when(sixFilteredStore.openRowsOfList("951", 100L)).thenAnswer(inv -> {
            Thread.currentThread().interrupt();                              // interrupted during the wait
            return working;
        });
        when(sixFilteredStore.openRowsOfList("235", 100L)).thenReturn(Collections.emptyList());

        export.filteringSixRawFiles(batchExport);

        verify(sixExportRunGuard).listFailed("INSTR", 10L, 100L, "951", 7L);
        verify(sixFilteredStore).dropPending(500L);
        verify(sixFilteredStore).announce(501L);
        verify(emailService).sendEmailDjImportGeneralError(eq(list951), anyString());
    }

    // ------------------------------------------------------------------ one list fails

    @Test
    public void listErrors_ineveryStage_otherListsContinue() {
        ReglissList list1 = list(1L, "1");
        ReglissList list2 = list(2L, "2");
        ReglissList list3 = list(3L, "3");
        ReglissList list4 = list(4L, "4");
        when(params.getGeneratedFileIds()).thenReturn(Arrays.asList(1L, 2L, 3L, 4L));
        List<ReglissList> found = Arrays.asList(list1, list2, list3, list4);
        when(reglissListRepository.findByListRef(Arrays.asList("1", "2", "3", "4"))).thenReturn(found);
        when(sixFileFilterService.filter(eq(SixFileKind.INSTRUMENT), anyList(), any())).thenThrow(new IllegalStateException("ORA-1"))
                .thenReturn(Collections.singletonList("row"));
        when(sixFilteredStore.deleteVersionOfList(SixFileKind.INSTRUMENT, 100L, "2")).thenThrow(new IllegalStateException("ORA-2"));
        when(sixFilteredRowWriter.write(any(), anyList(), eq("3"), anyLong(), anyLong(), any(), anyDouble()))
                .thenThrow(new SixExportException(SixExportException.Stage.WRITE, "w", "3", "instrument", 7L, new IllegalStateException("x")));
        doThrow(new IllegalStateException("not pending")).when(sixFilteredStore).announce(503L);
        doThrow(new IllegalStateException("smtp")).when(emailService).sendEmailDjImportGeneralError(eq(list4), anyString());
        doThrow(new IllegalStateException("db")).when(sixExportRunGuard).listFailed(anyString(), anyLong(), anyLong(), eq("4"), any());

        export.filteringSixRawFiles(batchExport);

        verify(sixExportRunGuard, times(4)).listFailed(eq("INSTR"), eq(10L), eq(100L), anyString(), eq(7L));
        verify(sixFilteredStore, times(4)).dropPending(anyLong());
        verify(sixFilteredStore, times(2)).deleteVersionOfList(SixFileKind.INSTRUMENT, 100L, "2");   // + partial rows
        verify(sixFilteredStore, times(2)).deleteVersionOfList(SixFileKind.INSTRUMENT, 100L, "3");
        verify(sixFilteredStore, times(2)).deleteVersionOfList(SixFileKind.INSTRUMENT, 100L, "4");
        verify(emailService, times(4)).sendEmailDjImportGeneralError(any(), anyString());
    }

    @Test
    public void listFailedOnTheOtherServer_skipped() {
        SixFilteredPoller failed = new SixFilteredPoller("INSTR", SixExportRunGuard.FAILED, NOW, 10L, 100L, "951", 8L);
        when(sixExportRunGuard.listStop(100L, 7L, "951")).thenReturn(Optional.of(failed));
        doThrow(new IllegalStateException("db")).when(sixFilteredStore).dropPending(500L);
        export.filteringSixRawFiles(batchExport);
        verify(sixFileFilterService, times(1)).filter(any(), anyList(), any());
        verify(sixFilteredStore).announce(501L);
    }

    @Test
    public void listFailedOnTheOtherServer_dropped() {
        SixFilteredPoller failed = new SixFilteredPoller("INSTR", SixExportRunGuard.FAILED, NOW, 10L, 100L, "235", 8L);
        when(sixExportRunGuard.listStop(100L, 7L, "235")).thenReturn(Optional.of(failed));
        export.filteringSixRawFiles(batchExport);
        verify(sixFilteredStore).dropPending(501L);
        verify(sixFilteredStore).announce(500L);
    }

    // ------------------------------------------------------------------ the whole delivery stops

    @Test
    public void connectionLost_stopsEveryList() {
        when(sixFilteredRowWriter.write(any(), anyList(), eq("951"), anyLong(), anyLong(), any(), anyDouble()))
                .thenThrow(new CannotGetJdbcConnectionException("pool"));
        when(sixFilteredStore.deleteVersionOfList(SixFileKind.INSTRUMENT, 100L, "951")).thenReturn(0).thenThrow(new IllegalStateException("db"));

        SixExportException e = expectStop();

        assertSame(SixExportException.Stage.WRITE, e.getStage());
        assertTrue(e.stopsAllLists());
        verify(sixExportRunGuard).deliveryFailed("INSTR", 10L, 100L, "951", 7L);
        verify(emailService).sendEmailDjImportGeneralError(eq(list951), anyString());
        verify(sixFilteredStore).dropPendingRowsOfJob(7L);
        verify(sixFilteredStore, never()).announce(anyLong());
    }

    @Test
    public void deliveryStoppedByTheOtherServer_beforeTheFirstList() {
        SixFilteredPoller all = new SixFilteredPoller("STRUCT", SixExportRunGuard.FAILED_ALL, NOW, 11L, 101L, null, 8L);
        when(sixExportRunGuard.deliveryStop(100L, 7L)).thenReturn(Optional.of(all));
        doThrow(new IllegalStateException("db")).when(sixFilteredStore).dropPendingRowsOfJob(7L);

        SixExportException e = expectStop();

        assertTrue(e.getMessage(), e.getMessage().contains("SIX export STOPPED before the first list"));
        verify(emailService, never()).sendEmailDjImportGeneralError(any(), anyString());          // no second mail
        verify(sixFilteredPollerRepository, never()).saveAll(anyList());
    }

    @Test
    public void deliveryStoppedByTheOtherServer_beforeAList() {
        SixFilteredPoller all = new SixFilteredPoller("STRUCT", SixExportRunGuard.FAILED_ALL, NOW, 11L, 101L, "235", 8L);
        when(sixExportRunGuard.listStop(100L, 7L, "235")).thenReturn(Optional.of(all));
        SixExportException e = expectStop();
        assertTrue(e.getMessage(), e.getMessage().contains("SIX export STOPPED before list 235"));
        verify(sixFilteredStore).announce(500L);
    }

    @Test
    public void failureSignalsNotReadable_stopsEveryList() {
        when(sixExportRunGuard.listStop(100L, 7L, "951")).thenThrow(new IllegalStateException("ORA-3"));
        doThrow(new IllegalStateException("db")).when(sixExportRunGuard).deliveryFailed(anyString(), anyLong(), anyLong(), any(), any());
        SixExportException e = expectStop();
        assertSame(SixExportException.Stage.PREPARE, e.getStage());
        verify(emailService).sendEmailDjImportGeneralError(eq(raw), anyString());                 // no list in the error
    }

    // ------------------------------------------------------------------ preparation fails

    @Test
    public void prepare_noRawVersion() {
        when(params.getVersionOpt()).thenReturn(Optional.empty());
        SixExportException e = expectStop();
        assertTrue(e.getMessage(), e.getMessage().contains("SIX file version ?, job 7"));
        verify(sixExportRunGuard, never()).deliveryFailed(any(), anyLong(), anyLong(), any(), any());
        verify(emailService, never()).sendEmailDjImportGeneralError(any(), anyString());
    }

    @Test
    public void prepare_notASixRawList() {
        when(raw.getImportFileType()).thenReturn(ImportFileType.SIX_MAIN_FILE);
        SixExportException e = expectStop();
        assertTrue(e.getMessage(), e.getMessage().contains("is not a SIX raw list"));
        verify(sixExportRunGuard).deliveryFailed(isNull(), eq(10L), eq(100L), isNull(), eq(7L));
        verify(emailService).sendEmailDjImportGeneralError(eq(raw), anyString());
    }

    @Test
    public void prepare_versionWithoutList() {
        when(version.getList()).thenReturn(null);
        SixExportException e = expectStop();
        assertSame(SixExportException.Stage.PREPARE, e.getStage());
        verify(sixExportRunGuard, never()).deliveryFailed(any(), anyLong(), anyLong(), any(), any());
        verify(emailService, never()).sendEmailDjImportGeneralError(any(), anyString());
    }

    @Test
    public void prepare_noActiveOutputList() {
        when(reglissListRepository.findByListRef(anyList())).thenReturn(Collections.emptyList());
        doThrow(new IllegalStateException("smtp")).when(emailService).sendEmailDjImportGeneralError(any(), anyString());
        SixExportException e = expectStop();
        assertTrue(e.getMessage(), e.getMessage().contains("No active output list for the references [951, 235, 999]"));
        assertTrue(e.getMessage(), e.getMessage().contains("INSTRUMENT file version 100, delivery D1, job 7"));
    }
}
