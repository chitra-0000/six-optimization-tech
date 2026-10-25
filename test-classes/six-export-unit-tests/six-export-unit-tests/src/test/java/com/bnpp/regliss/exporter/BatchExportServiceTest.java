package com.bnpp.regliss.exporter;

import org.hibernate.Session;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import javax.persistence.EntityManager;
import javax.persistence.LockModeType;
import javax.persistence.LockTimeoutException;
import javax.persistence.OptimisticLockException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// TODO: re-add project imports for: BatchExportRepository, ReglissRequestContext, VersionRepository, ReglissListRepository,
// NodeDetailsSupplier, ListImportNodeLockRepository, BatchJobExecutionRepository, SixFilteredPollerRepository, BatchExport,
// BatchExportParams, BatchExportType, GenerationReason, BatchJobExecution, BatchJobType, FileIdsToGenerate, ImportFileType,
// ReglissException, ReglissList, User, Version

@RunWith(MockitoJUnitRunner.Silent.class)
public class BatchExportServiceTest {

    @InjectMocks
    private BatchExportService service;

    @Mock
    private BatchExportRepository batchExportRepository;
    @Mock
    private ReglissRequestContext requestContext;
    @Mock
    private EntityManager entityManager;
    @Mock
    private VersionRepository versionRepo;
    @Mock
    private ReglissListRepository reglissListRepository;
    @Mock
    private NodeDetailsSupplier nodeDetailsSupplier;
    @Mock
    private ListImportNodeLockRepository listImportNodeLockRepository;
    @Mock
    private BatchJobExecutionRepository batchJobExecutionRepository;
    @Mock
    private SixFilteredPollerRepository sixFilteredPollerRepository;

    private final Version version = mock(Version.class);

    @Before
    public void setUp() {
        when(requestContext.getUsername()).thenReturn("alice");
        when(versionRepo.getExactlyOne(5L)).thenReturn(version);
        when(batchJobExecutionRepository.findTop1ByJobTypeAndReglissListIdOrderByStartDateDesc(any(), any())).thenReturn(Optional.empty());
    }

    private List<BatchExport> saved(int times) {
        ArgumentCaptor<BatchExport> captor = ArgumentCaptor.forClass(BatchExport.class);
        verify(batchExportRepository, times(times)).save(captor.capture());
        return captor.getAllValues();
    }

    private static void assertParams(BatchExport export, BatchExportType type, List<Long> ids, String user, GenerationReason reason,
                                     Version version) {
        BatchExportParams p = export.getExportParams();
        assertSame(type, p.getExportType());
        assertEquals(ids, p.getGeneratedFileIds());
        assertEquals(user, p.getLauncherUsername());
        assertSame(reason, p.getReason());
        assertEquals(Optional.ofNullable(version), p.getVersionOpt());
    }

    private static ReglissList list(Long id, String reference) {
        ReglissList l = mock(ReglissList.class);
        when(l.getId()).thenReturn(id);
        when(l.getReference()).thenReturn(reference);
        return l;
    }

    // ------------------------------------------------------------------ requests

    @Test
    public void versionRegeneration_byTheUser() {
        service.persistVersionExportRegenerationInTx(5L, Arrays.asList(1L, 2L));
        assertParams(saved(1).get(0), BatchExportType.VERSION_GENERATED_FILE, Arrays.asList(1L, 2L), "alice",
                GenerationReason.REGENERATION, version);
    }

    @Test
    public void otherRequests() {
        service.persistConcatenatedListExportGenerationInTx(Collections.singletonList(3L), GenerationReason.GENERATION);
        service.persistAddModSupExportGenerationInTx(version, 4L, GenerationReason.GENERATION);
        service.persistAddModSupAuditTrailGenerationInTx(version, 6L, GenerationReason.REGENERATION);
        service.persistDeliveryReportExportGenerationInTx(version, 7L, GenerationReason.GENERATION);
        List<BatchExport> all = saved(4);
        assertParams(all.get(0), BatchExportType.CONCATENATED_LIST_GENERATED_FILE, Collections.singletonList(3L), User.SYS_USERNAME,
                GenerationReason.GENERATION, null);
        assertParams(all.get(1), BatchExportType.ADD_MOD_SUP_FILE, Collections.singletonList(4L), User.SYS_USERNAME,
                GenerationReason.GENERATION, version);
        assertParams(all.get(2), BatchExportType.ADD_MOD_SUP_AUDIT_TRAIL_FILE, Collections.singletonList(6L), "alice",
                GenerationReason.REGENERATION, version);
        assertParams(all.get(3), BatchExportType.VERSION_GENERATED_FILE, Collections.singletonList(7L), User.SYS_USERNAME,
                GenerationReason.GENERATION, version);
    }

    @Test
    public void atCertification_versionFilesAndAddModSup() {
        FileIdsToGenerate files = mock(FileIdsToGenerate.class);
        when(files.getExportFileIds()).thenReturn(Arrays.asList(1L, 2L));
        when(files.getAddModSupFileId()).thenReturn(9L);
        service.persistVersionExportGenerationAtCertificationOrConfirmation(5L, files);
        List<BatchExport> all = saved(2);
        assertParams(all.get(0), BatchExportType.VERSION_GENERATED_FILE, Arrays.asList(1L, 2L), User.SYS_USERNAME,
                GenerationReason.GENERATION, version);
        assertParams(all.get(1), BatchExportType.ADD_MOD_SUP_FILE, Collections.singletonList(9L), User.SYS_USERNAME,
                GenerationReason.GENERATION, version);
    }

    @Test
    public void atCertification_nothingToGenerate() {
        FileIdsToGenerate files = mock(FileIdsToGenerate.class);
        when(files.getExportFileIds()).thenReturn(Collections.emptyList());
        when(files.getAddModSupFileId()).thenReturn(null);
        service.persistVersionExportGenerationAtCertificationOrConfirmation(5L, files);
        verify(batchExportRepository, never()).save(any());
    }

    @Test
    public void sixRequests() {
        List<ReglissList> outputLists = Arrays.asList(list(1L, "951"), list(2L, "235"));
        when(reglissListRepository.findByDJFormatNotDeleted(ImportFileType.SIX_MAIN_FILE)).thenReturn(outputLists);
        service.persistSixFilteredFileGenerationStubInTx(BatchExportType.SIX_FILTERED_FILE_GENERATION, GenerationReason.GENERATION, version);
        service.persistSixImportedFileGenerationStubInTx(BatchExportType.SIX_CONFIDENCE_VALUES, GenerationReason.GENERATION, version, 77L);
        List<BatchExport> all = saved(2);
        assertParams(all.get(0), BatchExportType.SIX_FILTERED_FILE_GENERATION, Arrays.asList(951L, 235L), User.SYS_USERNAME,
                GenerationReason.GENERATION, version);
        assertParams(all.get(1), BatchExportType.SIX_CONFIDENCE_VALUES, Collections.singletonList(77L), User.SYS_USERNAME,
                GenerationReason.GENERATION, version);
    }

    @Test
    public void sixFilteredRequest_noOutputList_nothingSaved() {
        when(reglissListRepository.findByDJFormatNotDeleted(ImportFileType.SIX_MAIN_FILE)).thenReturn(Collections.emptyList());
        service.persistSixFilteredFileGenerationStubInTx(BatchExportType.SIX_FILTERED_FILE_GENERATION, GenerationReason.GENERATION, version);
        verify(batchExportRepository, never()).save(any());
    }

    @Test
    public void deleteProcessed() {
        service.deleteProcessedBatchExport(3L);
        verify(batchExportRepository).deleteById(3L);
    }

    // ------------------------------------------------------------------ lock

    @Test
    @SuppressWarnings("unchecked")
    public void tryLock_freeRow_taken() {
        BatchExport export = new BatchExport();
        LocalDateTime now = LocalDateTime.now();
        when(batchExportRepository.getExactlyOne(3L)).thenReturn(export);
        when(nodeDetailsSupplier.getNodeId()).thenReturn("node-a");
        when(requestContext.getRequestTime()).thenReturn(now);

        assertTrue(service.tryLockBatchExport(3L));

        ArgumentCaptor<Map> hints = ArgumentCaptor.forClass(Map.class);
        verify(entityManager).lock(eq(export), eq(LockModeType.PESSIMISTIC_READ), hints.capture());
        assertEquals(Session.LockRequest.PESSIMISTIC_NO_WAIT, hints.getValue().get("javax.persistence.lock.timeout"));
        verify(entityManager).refresh(export);
        assertEquals("node-a", export.getBatchNodeId());
        assertEquals(now, export.getStartTime());
    }

    @Test
    public void tryLock_takenByAnotherNode_orLockError() {
        BatchExport export = new BatchExport();
        export.setBatchNodeId("node-b");
        when(batchExportRepository.getExactlyOne(3L)).thenReturn(export);
        assertFalse(service.tryLockBatchExport(3L));

        doThrow(new LockTimeoutException()).when(entityManager).lock(any(), any(), anyMap());
        assertFalse(service.tryLockBatchExport(3L));
        doThrow(new OptimisticLockException()).when(entityManager).lock(any(), any(), anyMap());
        assertFalse(service.tryLockBatchExport(3L));
    }

    // ------------------------------------------------------------------ SIX regeneration button

    private void rawVersionAvailable(boolean available) {
        when(versionRepo.getLatestVersionIdOfInstruments()).thenReturn(10L);
        when(versionRepo.countFirstSixInstrumentRowOfVersion(10L)).thenReturn(available ? 1L : 0L);
    }

    @Test
    public void regenerationAvailable() {
        when(versionRepo.getLatestVersionIdOfInstruments()).thenReturn(null);
        assertFalse(service.isSixRegenerationAvailable());                  // nothing imported
        rawVersionAvailable(false);
        assertFalse(service.isSixRegenerationAvailable());
        rawVersionAvailable(true);
        assertTrue(service.isSixRegenerationAvailable());
    }

    @Test
    public void regeneration_oneRequestPerRawFile() {
        rawVersionAvailable(true);
        ReglissList output = list(9L, "951");
        when(reglissListRepository.findById(9L)).thenReturn(Optional.of(output));
        List<ReglissList> rawInstr = Collections.singletonList(list(1L, "RAW1"));
        when(reglissListRepository.findByDJFormatNotDeleted(ImportFileType.SIX_INSTRUMENTS_FILE)).thenReturn(rawInstr);
        BatchJobExecution finishedImport = mock(BatchJobExecution.class);
        when(finishedImport.isNotFinished()).thenReturn(false);
        when(batchJobExecutionRepository.findTop1ByJobTypeAndReglissListIdOrderByStartDateDesc(BatchJobType.BATCH_IMPORT_SIXRAW, 1L))
                .thenReturn(Optional.of(finishedImport));
        Version instruments = mock(Version.class);
        Version structure = mock(Version.class);
        when(versionRepo.getLatestVersionOfInstruments()).thenReturn(instruments);
        when(versionRepo.getLatestVersionOFStructure()).thenReturn(structure);

        service.persistSixFilteredFileGenerationStubInTx(9L);

        List<BatchExport> all = saved(2);
        assertParams(all.get(0), BatchExportType.SIX_FILTERED_FILE_REGENERATION, Collections.singletonList(951L), "alice",
                GenerationReason.REGENERATION, instruments);
        assertParams(all.get(1), BatchExportType.SIX_FILTERED_FILE_REGENERATION, Collections.singletonList(951L), "alice",
                GenerationReason.REGENERATION, structure);
    }

    @Test
    public void regeneration_noRawListConfigured_noImportCheck() {
        rawVersionAvailable(true);
        ReglissList output = list(9L, "235");
        when(reglissListRepository.findById(9L)).thenReturn(Optional.of(output));
        service.persistSixFilteredFileGenerationStubInTx(9L);
        saved(2);
        verify(listImportNodeLockRepository, never()).countByListIdIn(any());
    }

    @Test
    public void regeneration_unknownList_nothingSaved() {
        when(reglissListRepository.findById(9L)).thenReturn(Optional.empty());
        service.persistSixFilteredFileGenerationStubInTx(9L);
        verify(batchExportRepository, never()).save(any());
    }

    private void expectRefused(ReglissException.ErrorCode code) {
        try {
            service.persistSixFilteredFileGenerationStubInTx(9L);
            fail();
        } catch (ReglissException e) {
            assertSame(code, e.getErrorCode());
        }
        verify(batchExportRepository, never()).save(any());
    }

    @Test
    public void regeneration_refused_noRawVersion() {
        rawVersionAvailable(false);
        ReglissList output = list(9L, "951");
        when(reglissListRepository.findById(9L)).thenReturn(Optional.of(output));
        expectRefused(ReglissException.ErrorCode.SIX_NO_RAW_VERSION);
    }

    @Test
    public void regeneration_refused_rawListLocked() {
        rawVersionAvailable(true);
        ReglissList output = list(9L, "951");
        when(reglissListRepository.findById(9L)).thenReturn(Optional.of(output));
        List<ReglissList> rawStruct = Collections.singletonList(list(2L, "RAW2"));
        when(reglissListRepository.findByDJFormatNotDeleted(ImportFileType.SIX_STRUCTURED_FILE)).thenReturn(rawStruct);
        when(listImportNodeLockRepository.countByListIdIn(Collections.singletonList(2L))).thenReturn(1L);
        expectRefused(ReglissException.ErrorCode.SIX_IMPORT_IN_PROGRESS);
    }

    @Test
    public void regeneration_refused_importJobOpen() {
        rawVersionAvailable(true);
        ReglissList output = list(9L, "951");
        when(reglissListRepository.findById(9L)).thenReturn(Optional.of(output));
        List<ReglissList> rawOptions = Collections.singletonList(list(3L, "RAW3"));
        when(reglissListRepository.findByDJFormatNotDeleted(ImportFileType.SIX_OPTIONS_FILE)).thenReturn(rawOptions);
        BatchJobExecution open = mock(BatchJobExecution.class);
        when(open.isNotFinished()).thenReturn(true);
        when(batchJobExecutionRepository.findTop1ByJobTypeAndReglissListIdOrderByStartDateDesc(BatchJobType.BATCH_IMPORT_SIXRAW, 3L))
                .thenReturn(Optional.of(open));
        expectRefused(ReglissException.ErrorCode.SIX_IMPORT_IN_PROGRESS);
    }

    @Test
    public void regeneration_refused_requestPending_orRunning() {
        rawVersionAvailable(true);
        ReglissList output = list(9L, "951");
        when(reglissListRepository.findById(9L)).thenReturn(Optional.of(output));
        when(batchExportRepository.countSixRegenerationRequests("951")).thenReturn(1L);
        expectRefused(ReglissException.ErrorCode.SIX_REGENERATION_IN_PROGRESS);

        when(batchExportRepository.countSixRegenerationRequests("951")).thenReturn(0L);
        when(sixFilteredPollerRepository.countRunningRegenerationRows(eq("951"), any(LocalDateTime.class))).thenReturn(1L);
        expectRefused(ReglissException.ErrorCode.SIX_REGENERATION_IN_PROGRESS);
        verify(batchJobExecutionRepository, never()).findTop1ByJobTypeAndReglissListIdOrderByStartDateDesc(any(), anyLong());
        verify(listImportNodeLockRepository, never()).countByListIdIn(any());
        verify(reglissListRepository, times(6)).findByDJFormatNotDeleted(any());
    }
}
