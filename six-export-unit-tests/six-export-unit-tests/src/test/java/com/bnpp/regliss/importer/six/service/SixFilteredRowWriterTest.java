package com.bnpp.regliss.importer.six.service;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// TODO: re-add project imports for: ReglissRequestContext, BatchProgressService, FilteredDataMapper, InstrumentFile, StructuredFile, OptionsFile,
// FilteredInstrumentFile, FilteredStructuredFile, FilteredOptionsFile, FilteredSixTarget

@RunWith(MockitoJUnitRunner.Silent.class)
public class SixFilteredRowWriterTest {

    @InjectMocks
    private SixFilteredRowWriter writer;

    @Mock
    private SixJdbcBulkWriter bulkWriter;
    @Mock
    private FilteredDataMapper filteredDataMapper;
    @Mock
    private ReglissRequestContext requestContext;
    @Mock
    private BatchProgressService batchProgressService;

    /** Real progress (final class), reporting to the mocked BatchProgressService of job 7. */
    private SixExportProgress progress;

    @Before
    public void setUp() {
        progress = new SixExportProgress(batchProgressService, 7L);
        ReflectionTestUtils.setField(writer, "dbBatchSize", 2);
        ReflectionTestUtils.setField(writer, "threadPoolSize", 1);
        when(filteredDataMapper.filteredInstrumentFile(any(), anyString())).thenAnswer(inv -> mock(FilteredInstrumentFile.class));
        when(filteredDataMapper.filteredStructuredFile(any(), anyString())).thenAnswer(inv -> mock(FilteredStructuredFile.class));
        when(filteredDataMapper.filteredOptionsFile(any(), anyString())).thenAnswer(inv -> mock(FilteredOptionsFile.class));
    }

    @Test
    public void noRow_wholeShareAdded_nothingWritten() {
        assertEquals(0, writer.write(SixFileKind.INSTRUMENT, Collections.emptyList(), "951", 1L, 2L, progress, 40.0));
        verify(batchProgressService).incrementExportBatchForSix(7L, 40);
        verify(bulkWriter, never()).insertBatch(anyList(), anyLong(), anyLong(), any(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void instrument_batchesOfTheConfiguredSize() {
        when(bulkWriter.insertBatch(anyList(), eq(1L), eq(2L), any(Function.class), eq("SIX_INSTRU_ID"))).thenReturn(3);
        List<InstrumentFile> rows = Arrays.asList(mock(InstrumentFile.class), mock(InstrumentFile.class), mock(InstrumentFile.class));

        assertEquals(3, writer.write(SixFileKind.INSTRUMENT, rows, "951", 1L, 2L, progress, 40.0));

        ArgumentCaptor<List> batches = ArgumentCaptor.forClass(List.class);
        verify(bulkWriter, times(2)).insertBatch(batches.capture(), eq(1L), eq(2L), any(Function.class), eq("SIX_INSTRU_ID"));
        assertEquals(2, batches.getAllValues().get(0).size());
        assertEquals(1, batches.getAllValues().get(1).size());
        verify(batchProgressService, times(2)).incrementExportBatchForSix(7L, 20);
        verify(filteredDataMapper).filteredInstrumentFile(rows.get(0), "951");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void structured_and_options() {
        ReflectionTestUtils.setField(writer, "dbBatchSize", 0);            // at least 1 per batch
        ReflectionTestUtils.setField(writer, "threadPoolSize", 0);         // at least 1 thread
        assertEquals(1, writer.write(SixFileKind.STRUCTURED, Collections.singletonList(mock(StructuredFile.class)), "235",
                1L, 2L, progress, 10.0));
        verify(bulkWriter).insertBatch(anyList(), eq(1L), eq(2L), any(Function.class), eq("SIX_STRUCT_ID"));
        assertEquals(1, writer.write(SixFileKind.OPTIONS, Collections.singletonList(mock(OptionsFile.class)), "952",
                1L, 2L, progress, 10.0));
        verify(bulkWriter).insertBatch(anyList(), eq(1L), eq(2L), any(Function.class), eq("SIX_OPT_ID"));
        verify(batchProgressService, times(2)).incrementExportBatchForSix(7L, 10);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void targetsOfTheFilteredEntitiesAreWritten() {
        ArgumentCaptor<Function> targets = ArgumentCaptor.forClass(Function.class);
        writer.write(SixFileKind.INSTRUMENT, Collections.singletonList(mock(InstrumentFile.class)), "951", 1L, 2L, progress, 1.0);
        writer.write(SixFileKind.STRUCTURED, Collections.singletonList(mock(StructuredFile.class)), "951", 1L, 2L, progress, 1.0);
        writer.write(SixFileKind.OPTIONS, Collections.singletonList(mock(OptionsFile.class)), "951", 1L, 2L, progress, 1.0);
        verify(bulkWriter, times(3)).insertBatch(anyList(), anyLong(), anyLong(), targets.capture(), anyString());
        List<FilteredSixTarget> sixTargets = Collections.singletonList(mock(FilteredSixTarget.class));
        FilteredInstrumentFile i = mock(FilteredInstrumentFile.class);
        when(i.getSixTargets()).thenReturn(sixTargets);
        FilteredStructuredFile s = mock(FilteredStructuredFile.class);
        when(s.getSixTargets()).thenReturn(sixTargets);
        FilteredOptionsFile o = mock(FilteredOptionsFile.class);
        when(o.getSixTargets()).thenReturn(sixTargets);
        assertSame(sixTargets, targets.getAllValues().get(0).apply(i));
        assertSame(sixTargets, targets.getAllValues().get(1).apply(s));
        assertSame(sixTargets, targets.getAllValues().get(2).apply(o));
    }

    /** First batch fails: the next submit sees the error, the pool is stopped and the error is thrown (fail fast). */
    @Test
    @SuppressWarnings("unchecked")
    public void failedBatch_stopsTheWrite() {
        ReflectionTestUtils.setField(writer, "dbBatchSize", 1);
        when(bulkWriter.insertBatch(anyList(), anyLong(), anyLong(), any(Function.class), anyString()))
                .thenThrow(new IllegalStateException("ORA-00001"));
        List<InstrumentFile> rows = Arrays.asList(mock(InstrumentFile.class), mock(InstrumentFile.class), mock(InstrumentFile.class));
        try {
            writer.write(SixFileKind.INSTRUMENT, rows, "951", 1L, 2L, progress, 30.0);
            fail();
        } catch (SixImportException e) {
            assertEquals("ORA-00001", e.getCause().getMessage());
        }
        verify(batchProgressService, never()).incrementExportBatchForSix(anyLong(), anyInt());
    }

    /** Interrupted while waiting for a writer slot: the pool is stopped quietly and the interruption is reported. */
    @Test
    public void interruptedWhileSubmitting() {
        List<InstrumentFile> rows = Collections.singletonList(mock(InstrumentFile.class));
        Thread.currentThread().interrupt();
        try {
            writer.write(SixFileKind.INSTRUMENT, rows, "951", 1L, 2L, progress, 30.0);
            fail();
        } catch (SixImportException e) {
            assertTrue(e.getCause() instanceof InterruptedException);
        } finally {
            Thread.interrupted();
        }
    }
}
