package com.bnpp.regliss.importer.six.service;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// TODO: re-add project imports for: SixFilteredPoller, SixFilteredPollerRepository, BatchJobExecution,
// BatchJobExecutionRepository, ImportedFile, ImportedFileRepository, HeartbeatService

@RunWith(MockitoJUnitRunner.Silent.class)
public class SixExportRunGuardTest {

    private static final LocalDateTime NOW = LocalDateTime.now();
    private static final String DELIVERY = "20261004101500";

    @InjectMocks
    private SixExportRunGuard guard;

    @Mock
    private SixFilteredPollerRepository sixFilteredPollerRepository;
    @Mock
    private BatchJobExecutionRepository batchJobExecutionRepository;
    @Mock
    private ImportedFileRepository importedFileRepository;
    @Mock
    private HeartbeatService heartbeatService;

    @Before
    public void setUp() {
        file(1L, "GSM_1_202_EU_INSTR_FULL_20261004_101500.xml");
        file(2L, "GSM_1_202_EU_STRUCT_FULL_20261004_101500.xml");
        file(3L, "GSM_1_202_EU_INSTR_FULL_20261003_101500.xml");
        when(batchJobExecutionRepository.findById(anyLong())).thenReturn(Optional.empty());
        when(sixFilteredPollerRepository.findByStatus(anyString())).thenReturn(Collections.emptyList());
    }

    private void file(Long version, String name) {
        ImportedFile f = mock(ImportedFile.class);
        when(f.getOriginalFileName()).thenReturn(name);
        when(importedFileRepository.findByVersionId(version)).thenReturn(f);
    }

    private BatchJobExecution job(long id, String params, LocalDateTime start) {
        BatchJobExecution job = mock(BatchJobExecution.class);
        when(job.getJobParams()).thenReturn(params);
        when(job.getStartDate()).thenReturn(start);
        when(batchJobExecutionRepository.findById(id)).thenReturn(Optional.of(job));
        return job;
    }

    private static SixFilteredPoller failure(String status, Long version, String listRef, LocalDateTime at) {
        SixFilteredPoller p = new SixFilteredPoller("INSTR", status, at, 9L, version, listRef, 5L);
        p.setId(version == null ? 0L : version);
        return p;
    }

    @Test
    public void deliveryOf_cachedOnlyWhenKnown() {
        assertEquals(DELIVERY, guard.deliveryOf(1L));
        assertEquals(DELIVERY, guard.deliveryOf(1L));
        verify(importedFileRepository, times(1)).findByVersionId(1L);         // cached

        assertEquals("version:7", guard.deliveryOf(7L));                     // file not imported yet
        assertEquals("version:7", guard.deliveryOf(7L));
        verify(importedFileRepository, times(2)).findByVersionId(7L);         // not cached

        file(8L, "README.txt");
        assertEquals("version:8", guard.deliveryOf(8L));                     // not a SIX name
    }

    @Test
    public void deliveryKeyCache_keepsTheLastVersionsOnly() {
        for (long v = 1000; v < 1501; v++) {
            file(v, "GSM_INSTR_20261004_101500.xml");
            guard.deliveryOf(v);
        }
        guard.deliveryOf(1000L);                                              // dropped: read again
        verify(importedFileRepository, times(2)).findByVersionId(1000L);
        guard.deliveryOf(1500L);
        verify(importedFileRepository, times(1)).findByVersionId(1500L);
    }

    @Test
    public void listFailed_andDeliveryFailed_saved() {
        guard.listFailed("INSTR", 9L, 1L, "951", 5L);
        guard.deliveryFailed("STRUCT", 9L, 2L, null, null);
        ArgumentCaptor<SixFilteredPoller> saved = ArgumentCaptor.forClass(SixFilteredPoller.class);
        verify(sixFilteredPollerRepository, times(2)).save(saved.capture());
        assertEquals(SixExportRunGuard.FAILED, saved.getAllValues().get(0).getStatus());
        assertEquals("951", saved.getAllValues().get(0).getSixListReference());
        assertEquals(SixExportRunGuard.FAILED_ALL, saved.getAllValues().get(1).getStatus());
        assertEquals("STRUCT", saved.getAllValues().get(1).getFileType());
    }

    @Test
    public void stops_withoutJob_allRowsOfTheSameDelivery() {
        SixFilteredPoller otherDelivery = failure(SixExportRunGuard.FAILED_ALL, 3L, null, NOW);
        SixFilteredPoller noVersion = failure(SixExportRunGuard.FAILED_ALL, null, null, NOW);
        SixFilteredPoller sameDelivery = failure(SixExportRunGuard.FAILED_ALL, 2L, null, NOW);
        when(sixFilteredPollerRepository.findByStatus(SixExportRunGuard.FAILED_ALL))
                .thenReturn(Arrays.asList(otherDelivery, noVersion, sameDelivery));

        assertSame(sameDelivery, guard.deliveryStop(1L, null).get());
        assertSame(otherDelivery, guard.deliveryStop(3L, null).get());
    }

    @Test
    public void listStop_listFailureFirst_elseDeliveryFailure() {
        SixFilteredPoller list951 = failure(SixExportRunGuard.FAILED, 1L, "951", NOW);
        when(sixFilteredPollerRepository.findByStatus(SixExportRunGuard.FAILED)).thenReturn(Collections.singletonList(list951));
        assertSame(list951, guard.listStop(1L, null, "951").get());

        assertFalse(guard.listStop(1L, null, "235").isPresent());           // no FAILED_ALL either
        SixFilteredPoller all = failure(SixExportRunGuard.FAILED_ALL, 1L, null, NOW);
        when(sixFilteredPollerRepository.findByStatus(SixExportRunGuard.FAILED_ALL)).thenReturn(Collections.singletonList(all));
        assertSame(all, guard.listStop(1L, null, "235").get());
    }

    @Test
    public void stops_ofAJob_readSinceOneDayBeforeItsStart() {
        LocalDateTime start = NOW.minusHours(1);
        job(5L, "{\"type\":\"GENERATION\"}", start);
        SixFilteredPoller f = failure(SixExportRunGuard.FAILED_ALL, 1L, null, start.minusMinutes(5));
        when(sixFilteredPollerRepository.findByStatusAndInsertionTimeGreaterThanEqual(SixExportRunGuard.FAILED_ALL, start.minusHours(24)))
                .thenReturn(Collections.singletonList(f));
        assertSame(f, guard.deliveryStop(1L, 5L).get());                    // not a regeneration: older failures count
        verify(sixFilteredPollerRepository, never()).findByStatus(anyString());

        job(6L, null, null);                                                // job without start date: all rows
        when(sixFilteredPollerRepository.findByStatus(SixExportRunGuard.FAILED_ALL)).thenReturn(Collections.singletonList(f));
        assertSame(f, guard.deliveryStop(1L, 6L).get());
    }

    @Test
    public void stops_ofARegeneration_onlyFailuresAfterItsStart() {
        LocalDateTime start = NOW.minusHours(1);
        job(5L, "{\"reason\":\"REGENERATION\"}", start);
        SixFilteredPoller before = failure(SixExportRunGuard.FAILED_ALL, 1L, null, start.minusMinutes(5));
        SixFilteredPoller noTime = failure(SixExportRunGuard.FAILED_ALL, 1L, null, null);
        SixFilteredPoller after = failure(SixExportRunGuard.FAILED_ALL, 1L, null, start.plusMinutes(5));
        when(sixFilteredPollerRepository.findByStatusAndInsertionTimeGreaterThanEqual(eq(SixExportRunGuard.FAILED_ALL), any()))
                .thenReturn(Arrays.asList(before, noTime, after));
        assertSame(after, guard.deliveryStop(1L, 5L).get());
    }

    @Test
    public void describe() {
        SixFilteredPoller all = failure(SixExportRunGuard.FAILED_ALL, 1L, null, NOW);
        assertEquals("the export of delivery " + DELIVERY + " failed at " + NOW + " (raw version 1, job 5)", guard.describe(all));
        SixFilteredPoller list = failure(SixExportRunGuard.FAILED, 1L, "951", NOW);
        assertEquals("list 951 of delivery " + DELIVERY + " failed at " + NOW + " (raw version 1, job 5)", guard.describe(list));
    }

    @Test
    public void generationReasonOf() {
        assertEquals(SixExportRunGuard.GENERATION, guard.generationReasonOf(null));
        assertEquals(SixExportRunGuard.GENERATION, guard.generationReasonOf(4L));          // unknown job
        job(5L, null, NOW);
        assertEquals(SixExportRunGuard.GENERATION, guard.generationReasonOf(5L));
        job(6L, "GENERATION", NOW);
        assertEquals(SixExportRunGuard.GENERATION, guard.generationReasonOf(6L));
        job(7L, "x REGENERATION y", NOW);
        assertEquals(SixExportRunGuard.REGENERATION, guard.generationReasonOf(7L));
    }

    @Test
    public void isJobWorking() {
        assertFalse(guard.isJobWorking(null));
        assertFalse(guard.isJobWorking(4L));                                   // unknown job

        BatchJobExecution finished = job(5L, null, NOW);
        when(finished.isFinished()).thenReturn(true);
        assertFalse(guard.isJobWorking(5L));

        BatchJobExecution neverUpdated = job(6L, null, NOW);
        assertFalse(guard.isJobWorking(6L));

        BatchJobExecution stale = job(7L, null, NOW);
        when(stale.getLastUpdateDate()).thenReturn(NOW.minusMinutes(31));
        assertFalse(guard.isJobWorking(7L));

        BatchJobExecution deadServer = job(8L, null, NOW);
        when(deadServer.getLastUpdateDate()).thenReturn(NOW);
        when(deadServer.getNodeId()).thenReturn("node-dead");
        assertFalse(guard.isJobWorking(8L));

        BatchJobExecution working = job(9L, null, NOW);
        when(working.getLastUpdateDate()).thenReturn(NOW);
        when(working.getNodeId()).thenReturn("node-ok");
        when(heartbeatService.isNodeAlive("node-ok")).thenReturn(true);
        assertTrue(guard.isJobWorking(9L));
    }
}
