package com.bnpp.regliss.importer.six.service;

import com.bnpp.regliss.scheduler.AutomaticSixImportFileRepository;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import javax.persistence.EntityManager;
import javax.persistence.TypedQuery;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// TODO: re-add project imports for: AutomaticImportFeedBase, BatchExportRepository, BatchExportService, BatchExport,
// BatchExportParams, BatchExportType, GenerationReason, BatchJobExecution, BatchManagementService, BatchProgressService,
// UnblockImportService, ReglissListRepository, ReglissList, EmailService, Version, User, NodeDetailsSupplier, ImportFileType

@RunWith(MockitoJUnitRunner.Silent.class)
public class SixDeliveryServiceTest {

    private static final long K = 20261004101500L;
    private static final long K0 = 20261003101500L;
    private static final String SUFFIX_K = "_20261004_101500.xml";
    private static final String SUFFIX_K0 = "_20261003_101500.xml";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @InjectMocks
    private SixDeliveryService service;

    @Mock
    private AutomaticSixImportFileRepository sixFileRepo;
    @Mock
    private BatchExportRepository batchExportRepository;
    @Mock
    private BatchExportService batchExportService;
    @Mock
    private BatchManagementService batchManagementService;
    @Mock
    private BatchProgressService batchProgressService;
    @Mock
    private UnblockImportService unblockImportService;
    @Mock
    private ReglissListRepository reglissListRepository;
    @Mock
    private EmailService emailService;
    @Mock
    private NodeDetailsSupplier nodeDetailsSupplier;
    @Mock
    private SixConfidenceStore store;
    @Mock
    private EntityManager entityManager;
    @Mock
    private PlatformTransactionManager transactionManager;

    private final List<BatchExport> exports = new ArrayList<>();
    private final List<BatchJobExecution> openJobs = new ArrayList<>();
    private File in;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() throws IOException {
        in = tmp.newFolder("in");
        ReflectionTestUtils.setField(service, "sixFileNamePrefix", "GSM");
        when(sixFileRepo.getInputDirectory()).thenReturn(in);
        when(batchExportRepository.getByBatchType()).thenAnswer(inv -> exports);
        TypedQuery<BatchJobExecution> query = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(BatchJobExecution.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenAnswer(inv -> openJobs);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        list(1L, ImportFileType.SIX_INSTRUMENTS_FILE);
        list(2L, ImportFileType.SIX_STRUCTURED_FILE);
        list(3L, ImportFileType.SIX_OPTIONS_FILE);
    }

    // ------------------------------------------------------------------ helpers

    private ReglissList list(Long id, ImportFileType type) {
        ReglissList l = mock(ReglissList.class);
        when(l.getImportFileType()).thenReturn(type);
        when(reglissListRepository.getExactlyOne(id)).thenReturn(l);
        return l;
    }

    private BatchExport export(long exportId, long jobId, Long key, ImportFileType type, Long versionId, String node,
                               String launcher) {
        Version version = null;
        if (type != null) {
            version = mock(Version.class);
            when(version.getId()).thenReturn(versionId);
            when(version.getImportFileType()).thenReturn(type);
        }
        BatchExportParams params = mock(BatchExportParams.class);
        when(params.getGeneratedFileIds()).thenReturn(Collections.singletonList(jobId));
        when(params.getLauncherUsername()).thenReturn(launcher);
        when(params.getVersionOpt()).thenReturn(Optional.ofNullable(version));
        BatchExport e = mock(BatchExport.class);
        when(e.getId()).thenReturn(exportId);
        when(e.getExportParams()).thenReturn(params);
        when(store.currentNodeOfRow(exportId)).thenReturn(node);
        when(store.deliveryKeyOfJob(jobId)).thenReturn(key);
        exports.add(e);
        return e;
    }

    private BatchExport exportWithoutJob(long exportId, List<Long> ids) {
        BatchExportParams params = mock(BatchExportParams.class);
        when(params.getGeneratedFileIds()).thenReturn(ids);
        BatchExport e = mock(BatchExport.class);
        when(e.getId()).thenReturn(exportId);
        when(e.getExportParams()).thenReturn(params);
        exports.add(e);
        return e;
    }

    private BatchJobExecution job(long id, Long listId, Long key) {
        BatchJobExecution j = mock(BatchJobExecution.class);
        when(j.getId()).thenReturn(id);
        when(j.getReglissListId()).thenReturn(listId);
        when(store.deliveryKeyOfJob(id)).thenReturn(key);
        return j;
    }

    private void openJob(long id, Long listId, Long key) {
        openJobs.add(job(id, listId, key));
    }

    private String inFile(String name) throws IOException {
        Files.write(new File(in, name).toPath(), "x".getBytes(StandardCharsets.UTF_8));
        return name;
    }

    private AutomaticImportFeedBase feed(String fileName) {
        AutomaticImportFeedBase feed = mock(AutomaticImportFeedBase.class);
        when(feed.getXmlDataFileName()).thenReturn(fileName);
        return feed;
    }

    // ------------------------------------------------------------------ delivery key

    @Test
    public void deliveryKey() {
        assertNull(SixDeliveryService.deliveryKey(null));
        assertNull(SixDeliveryService.deliveryKey("README.xml"));
        assertEquals(Long.valueOf(K), SixDeliveryService.deliveryKey("gsm_1_202_instr_full" + SUFFIX_K));
        assertNull(SixDeliveryService.deliveryKey("GSM_INSTR_2026_1004_101500.xml"));     // not a number
    }

    // ------------------------------------------------------------------ after import

    @Test
    public void afterSuccessfulImport_fileStillInInput_waits() throws IOException {
        String name = inFile("GSM_INSTR" + SUFFIX_K);
        when(sixFileRepo.getInputFileByName(name)).thenReturn(new File(in, name));
        assertTrue(service.afterSuccessfulImport(feed(name)));
        verify(sixFileRepo, never()).moveToErrorDirectoryIfPresent(anyString());
    }

    @Test
    public void afterSuccessfulImport_fileGone_rollsBack() throws IOException {
        String name = "GSM_STRUCT" + SUFFIX_K;
        when(sixFileRepo.getInputFileByName(name)).thenReturn(new File(in, name));
        String other = inFile("GSM_INSTR" + SUFFIX_K);
        assertFalse(service.afterSuccessfulImport(feed(name)));
        verify(sixFileRepo).moveToErrorDirectoryIfPresent(other);
    }

    @Test
    public void afterFailedImport_beforeJobCreated_unlocksList() {
        service.afterFailedImport(feed("GSM_INSTR" + SUFFIX_K), 7L, null, new IllegalStateException("boom"));
        verify(batchManagementService).attemptUnlockListWithCurrentNode(7L);
        verify(unblockImportService, never()).finishSixImportExecutionByRequesterUser(anyLong(), anyString());
    }

    @Test
    public void afterFailedImport_withJob_closesJob() {
        service.afterFailedImport(feed("GSM_INSTR" + SUFFIX_K), 7L, 99L, new IllegalStateException("boom"));
        verify(unblockImportService).finishSixImportExecutionByRequesterUser(99L, User.SYS_USERNAME);
        verify(batchManagementService, never()).attemptUnlockListWithCurrentNode(any());
    }

    @Test
    public void afterFailedImport_noJobNoList() {
        service.afterFailedImport(feed("OTHER.xml"), null, null, new IllegalStateException("boom"));
        verify(batchManagementService, never()).attemptUnlockListWithCurrentNode(any());
        verify(sixFileRepo, never()).getInputDirectory();
    }

    // ------------------------------------------------------------------ rollback

    @Test
    public void rollbackDelivery_filesToErrorAndWaitingRowsRolledBack() throws IOException {
        String instr = inFile("GSM_INSTR" + SUFFIX_K);
        String struct = inFile("GSM_STRUCT" + SUFFIX_K);
        String opt = inFile("GSM_OPT" + SUFFIX_K);
        String olderInstr = inFile("GSM_INSTR" + SUFFIX_K0);
        String noPrefix = inFile("XXX_INSTR" + SUFFIX_K);
        String noKey = inFile("GSM_README.xml");
        String unknownKind = inFile("GSM_FOO" + SUFFIX_K);
        assertTrue(new File(in, "GSM_DIR" + SUFFIX_K).mkdir());
        when(sixFileRepo.moveToErrorDirectoryIfPresent(instr)).thenReturn(true);
        when(sixFileRepo.moveToErrorDirectoryIfPresent(struct)).thenReturn(false);
        when(sixFileRepo.moveToErrorDirectoryIfPresent(opt)).thenThrow(new IllegalStateException("disk"));

        export(1L, 11L, K, ImportFileType.SIX_INSTRUMENTS_FILE, 100L, null, "bob");     // mail sent
        export(2L, 12L, K, ImportFileType.SIX_STRUCTURED_FILE, 101L, null, null);       // job not found, close fails
        export(3L, 13L, K, null, null, null, null);                                     // no version, job without list
        export(4L, 14L, K0, ImportFileType.SIX_OPTIONS_FILE, 102L, null, null);         // other delivery
        export(5L, 15L, K, ImportFileType.SIX_OPTIONS_FILE, 103L, null, null);          // row delete fails
        exportWithoutJob(6L, null);
        exportWithoutJob(7L, Collections.emptyList());
        export(8L, 18L, K, ImportFileType.SIX_OPTIONS_FILE, 104L, null, null);          // mail fails

        BatchJobExecution job11 = job(11L, 1L, K);
        BatchJobExecution job13 = job(13L, null, K);
        BatchJobExecution job18 = job(18L, 3L, K);
        when(entityManager.find(BatchJobExecution.class, 11L)).thenReturn(job11);
        when(entityManager.find(BatchJobExecution.class, 13L)).thenReturn(job13);
        when(entityManager.find(BatchJobExecution.class, 18L)).thenReturn(job18);
        doThrow(new IllegalStateException("db")).when(unblockImportService).finishSixImportExecutionByRequesterUser(12L, null);
        doThrow(new IllegalStateException("db")).when(store).deleteRow(5L);
        ReglissList optList = reglissListRepository.getExactlyOne(3L);
        doThrow(new IllegalStateException("smtp")).when(emailService).sendEmailDjImportGeneralError(eq(optList), anyString());

        service.rollbackDelivery(K, 99L, "test");

        verify(sixFileRepo).moveToErrorDirectoryIfPresent(instr);
        verify(sixFileRepo).moveToErrorDirectoryIfPresent(struct);
        verify(sixFileRepo).moveToErrorDirectoryIfPresent(opt);
        verify(sixFileRepo, never()).moveToErrorDirectoryIfPresent(olderInstr);
        verify(sixFileRepo, never()).moveToErrorDirectoryIfPresent(noPrefix);
        verify(sixFileRepo, never()).moveToErrorDirectoryIfPresent(noKey);
        verify(sixFileRepo, never()).moveToErrorDirectoryIfPresent(unknownKind);

        verify(store).deleteRow(1L);
        verify(unblockImportService).tryDeleteSixAutomaticVersionAndRecordsInTx(100L);
        verify(unblockImportService).finishSixImportExecutionByRequesterUser(11L, "bob");
        verify(emailService).sendEmailDjImportGeneralError(eq(reglissListRepository.getExactlyOne(1L)), anyString());

        verify(store).deleteRow(2L);
        verify(unblockImportService).tryDeleteSixAutomaticVersionAndRecordsInTx(101L);

        verify(store).deleteRow(3L);
        verify(unblockImportService).finishSixImportExecutionByRequesterUser(13L, null);

        verify(store, never()).deleteRow(4L);
        verify(unblockImportService, never()).tryDeleteSixAutomaticVersionAndRecordsInTx(102L);
        verify(unblockImportService, never()).tryDeleteSixAutomaticVersionAndRecordsInTx(103L);   // stopped at row delete
        verify(unblockImportService).tryDeleteSixAutomaticVersionAndRecordsInTx(104L);
        verify(emailService, times(2)).sendEmailDjImportGeneralError(any(), anyString());

        verify(unblockImportService).finishSixImportExecutionByRequesterUser(99L, User.SYS_USERNAME);
    }

    @Test
    public void rollbackDelivery_noKeyNoJob_doesNothing() {
        service.rollbackDelivery(null, null, "test");
        verify(sixFileRepo, never()).getInputDirectory();
        verify(unblockImportService, never()).finishSixImportExecutionByRequesterUser(any(), any());
    }

    @Test
    public void rollbackDelivery_inputFolderNotReadable() {
        when(sixFileRepo.getInputDirectory()).thenReturn(new File(in, "missing"));
        try {
            service.rollbackDelivery(K, null, "test");
            fail();
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("missing"));
        }
    }

    // ------------------------------------------------------------------ delivery state

    @Test
    public void findWaitingDelivery_nothingWaiting() {
        assertFalse(service.findWaitingDelivery().isPresent());
    }

    @Test
    public void findWaitingDelivery_rowsWithoutKey() {
        export(1L, 11L, null, ImportFileType.SIX_INSTRUMENTS_FILE, 100L, null, null);
        assertFalse(service.findWaitingDelivery().isPresent());
        verify(store, never()).deleteRow(anyLong());
    }

    @Test
    public void findWaitingDelivery_latestDelivery_leftoversSuperseded() throws IOException {
        export(21L, 21L, K, ImportFileType.SIX_INSTRUMENTS_FILE, null, null, null);   // lower than 22 (no version id)
        export(22L, 22L, K, ImportFileType.SIX_INSTRUMENTS_FILE, 10L, null, null);
        export(23L, 23L, K, ImportFileType.SIX_STRUCTURED_FILE, 20L, null, null);
        export(24L, 24L, K, ImportFileType.SIX_STRUCTURED_FILE, 19L, null, "bob");    // lower than 23
        export(25L, 25L, K, ImportFileType.SIX_MAIN_FILE, 30L, null, null);           // not a SIX kind: kept, ignored
        export(26L, 26L, null, ImportFileType.SIX_OPTIONS_FILE, 31L, null, null);     // no key: kept, ignored
        export(27L, 27L, K0, ImportFileType.SIX_OPTIONS_FILE, 5L, null, null);        // older delivery

        openJob(22L, 1L, K);
        openJob(31L, 3L, K);
        openJob(32L, null, K);
        openJob(33L, 2L, 20261005101500L);
        openJob(27L, 3L, K0);
        openJob(34L, 2L, K0);                                                         // older STRUCT still importing

        String instr = inFile("GSM_INSTR" + SUFFIX_K);
        String struct = inFile("GSM_STRUCT" + SUFFIX_K);
        String olderStruct = inFile("GSM_STRUCT" + SUFFIX_K0);
        String olderInstr = inFile("GSM_INSTR" + SUFFIX_K0);
        String olderOpt = inFile("GSM_OPT" + SUFFIX_K0);
        when(sixFileRepo.moveToIgnoreDirectoryIfPresent(olderInstr)).thenReturn(true);
        when(sixFileRepo.moveToIgnoreDirectoryIfPresent(olderOpt)).thenReturn(false);

        SixDeliveryService.Delivery d = service.findWaitingDelivery().get();

        assertEquals(Long.valueOf(K), d.getKey());
        SixDeliveryService.DeliveryFile i = d.instrument().get();
        assertEquals(SixDeliveryService.FileState.WAITING, i.state());
        assertEquals(22L, i.getRow().getExportId());
        assertEquals(Long.valueOf(22L), i.getJobId());
        assertEquals(instr, i.fileInInput);
        assertTrue(i.jobOpen);
        SixDeliveryService.DeliveryFile s = d.file(SixFileKind.STRUCTURED);
        assertEquals(Long.valueOf(20L), s.getRow().getVersionId());
        assertEquals(struct, s.fileInInput);
        SixDeliveryService.DeliveryFile o = d.file(SixFileKind.OPTIONS);
        assertEquals(SixDeliveryService.FileState.RUNNING, o.state());
        assertEquals(Long.valueOf(31L), o.getJobId());

        verify(store).deleteRow(21L);
        verify(store).deleteRow(24L);
        verify(unblockImportService).finishSixImportExecutionByRequesterUser(24L, "bob");
        verify(store, times(2)).deleteRow(27L);
        verify(unblockImportService).finishSixImportExecutionByRequesterUser(27L, null);
        verify(store, never()).deleteRow(22L);
        verify(store, never()).deleteRow(25L);
        verify(store, never()).deleteRow(26L);
        verify(sixFileRepo, never()).moveToIgnoreDirectoryIfPresent(olderStruct);
        verify(sixFileRepo).moveToIgnoreDirectoryIfPresent(olderInstr);
        verify(sixFileRepo).moveToIgnoreDirectoryIfPresent(olderOpt);
    }

    @Test
    public void findWaitingDelivery_supersedeFailures_retriedNextTick() throws IOException {
        export(1L, 11L, K, ImportFileType.SIX_INSTRUMENTS_FILE, 10L, null, null);
        export(2L, 12L, K, ImportFileType.SIX_INSTRUMENTS_FILE, 9L, null, null);      // superseded, delete fails
        export(3L, 13L, K0, ImportFileType.SIX_STRUCTURED_FILE, 5L, null, null);      // older, move fails
        doThrow(new IllegalStateException("db")).when(store).deleteRow(2L);
        String olderStruct = inFile("GSM_STRUCT" + SUFFIX_K0);
        when(sixFileRepo.moveToIgnoreDirectoryIfPresent(olderStruct)).thenThrow(new IllegalStateException("disk"));

        assertEquals(Long.valueOf(K), service.findWaitingDelivery().get().getKey());

        verify(unblockImportService, never()).finishSixImportExecutionByRequesterUser(eq(12L), any());
        verify(store).deleteRow(3L);                                                  // only the first delete
        verify(unblockImportService, never()).finishSixImportExecutionByRequesterUser(eq(13L), any());
    }

    @Test
    public void findWaitingDelivery_finishingDeliveryFirst_noSupersede() {
        export(1L, 11L, K0, ImportFileType.SIX_INSTRUMENTS_FILE, 10L, "n1", null);    // older but finishing
        export(2L, 12L, K, ImportFileType.SIX_STRUCTURED_FILE, 20L, null, null);
        export(3L, 13L, K0, ImportFileType.SIX_STRUCTURED_FILE, 11L, "n2", null);

        SixDeliveryService.Delivery d = service.findWaitingDelivery().get();

        assertEquals(Long.valueOf(K0), d.getKey());
        assertTrue(d.isFinishing());
        assertEquals(2, d.members().size());
        verify(store, never()).deleteRow(anyLong());
    }

    // ------------------------------------------------------------------ finish

    @Test
    public void finishFile_dependent_exportThenUnlock() {
        SixDeliveryService.Delivery d = SixDeliveryFixtures.delivery(K);
        SixDeliveryService.DeliveryFile f = SixDeliveryFixtures.waiting(d, SixFileKind.STRUCTURED, 5L, 20L, "n1", 12L);
        f.jobOpen = true;
        f.row.launcher = "bob";
        f.row.version = mock(Version.class);

        service.finishFile(d, f);

        verify(sixFileRepo).deleteInputFile(f.fileInInput);
        verify(batchManagementService).updateBatchExecutionCompletedProgress(12L);
        verify(batchExportService).persistSixFilteredFileGenerationStubInTx(BatchExportType.SIX_FILTERED_FILE_GENERATION,
                GenerationReason.GENERATION, f.row.version);
        verify(store).deleteRow(5L);
        verify(unblockImportService).finishSixImportExecutionByRequesterUser(12L, "bob");
    }

    @Test
    public void finishFile_instrument_alreadyExported() {
        SixDeliveryService.Delivery d = SixDeliveryFixtures.delivery(K);
        SixDeliveryService.DeliveryFile f = SixDeliveryFixtures.running(d, SixFileKind.INSTRUMENT, 11L);
        f.jobOpen = false;

        service.finishFile(d, f);

        verify(sixFileRepo, never()).deleteInputFile(anyString());
        verify(batchManagementService, never()).updateBatchExecutionCompletedProgress(any());
        verify(batchExportService, never()).persistSixFilteredFileGenerationStubInTx(any(), any(), any());
        verify(unblockImportService).finishSixImportExecutionByRequesterUser(11L, User.SYS_USERNAME);
    }

    @Test
    public void finishFile_instrument_withRow_noLauncher() {
        SixDeliveryService.Delivery d = SixDeliveryFixtures.delivery(K);
        SixDeliveryService.DeliveryFile f = SixDeliveryFixtures.waiting(d, SixFileKind.INSTRUMENT, 1L, 10L, "n1", 11L);

        service.finishFile(d, f);

        verify(store).deleteRow(1L);
        verify(unblockImportService).finishSixImportExecutionByRequesterUser(11L, User.SYS_USERNAME);
    }

    @Test
    public void finishFile_noJob_closeSkipped() {
        SixDeliveryService.Delivery d = SixDeliveryFixtures.delivery(K);
        SixDeliveryService.DeliveryFile f = SixDeliveryFixtures.pending(d, SixFileKind.OPTIONS);
        service.finishFile(d, f);
        verify(unblockImportService, never()).finishSixImportExecutionByRequesterUser(any(), any());
    }

    // ------------------------------------------------------------------ keep-alive

    @Test
    public void keepWaitingFilesAlive_onceAMinute() {
        export(1L, 11L, K, ImportFileType.SIX_INSTRUMENTS_FILE, 10L, null, null);
        export(2L, 12L, K, ImportFileType.SIX_STRUCTURED_FILE, 20L, null, null);

        service.keepWaitingFilesAlive(12L);
        service.keepWaitingFilesAlive(12L);                    // within the minute: skipped

        verify(batchProgressService, times(1)).incrementExportBatchForSix(11L, 0);
        verify(batchProgressService, never()).incrementExportBatchForSix(12L, 0);
    }

    @Test
    public void keepWaitingFilesAlive_errorIgnored() {
        when(batchExportRepository.getByBatchType()).thenThrow(new IllegalStateException("db"));
        service.keepWaitingFilesAlive(1L);
        verify(batchProgressService, never()).incrementExportBatchForSix(any(), anyInt());
    }

    @Test
    public void currentNodeId() {
        when(nodeDetailsSupplier.getNodeId()).thenReturn("n1");
        assertEquals("n1", service.currentNodeId());
    }

    // ------------------------------------------------------------------ value classes

    @Test
    public void waitingRow_gettersAndToString() {
        SixDeliveryService.Delivery d = SixDeliveryFixtures.delivery(K);
        SixDeliveryService.WaitingRow r = SixDeliveryFixtures.waiting(d, SixFileKind.OPTIONS, 3L, 7L, "n", 9L).getRow();
        assertEquals(Long.valueOf(7L), r.getVersionId());
        assertEquals(3L, r.getExportId());
        assertEquals("n", r.getBatchNodeId());
        assertEquals("{export 3, job 9, OPTIONS, key " + K + "}", r.toString());
    }

    @Test
    public void deliveryFile_statesAndConfidence() {
        SixDeliveryService.Delivery d = SixDeliveryFixtures.delivery(K);
        SixDeliveryService.DeliveryFile f = d.file(SixFileKind.STRUCTURED);
        assertSame(SixFileKind.STRUCTURED, f.getKind());
        assertEquals(SixDeliveryService.FileState.ABSENT, f.state());
        assertFalse(f.confidenceDone());
        f.fileInInput = "x";
        assertEquals(SixDeliveryService.FileState.PENDING, f.state());
        f.jobOpen = true;
        assertEquals(SixDeliveryService.FileState.RUNNING, f.state());
        SixDeliveryFixtures.waiting(d, SixFileKind.STRUCTURED, 1L, 2L, null, 3L);
        assertEquals(SixDeliveryService.FileState.WAITING, f.state());
        assertFalse(f.confidenceDone());
        f.row.batchNodeId = "n";
        assertTrue(f.confidenceDone());
        assertEquals("STRUCTURED=WAITING", f.toString());
        assertTrue(d.file(SixFileKind.INSTRUMENT).confidenceDone());
    }

    @Test
    public void delivery_states() {
        SixDeliveryService.Delivery d = SixDeliveryFixtures.delivery(K);
        assertFalse(d.instrument().isPresent());
        assertFalse(d.isFinishing());
        d.file(SixFileKind.INSTRUMENT);                                     // ABSENT: not a member
        assertFalse(d.instrument().isPresent());
        assertTrue(d.members().isEmpty());

        SixDeliveryService.DeliveryFile i = SixDeliveryFixtures.pending(d, SixFileKind.INSTRUMENT);
        assertTrue(d.instrument().isPresent());
        assertFalse(d.isFinishing());                                       // no row
        assertTrue(d.hasPendingOrRunning());
        assertFalse(d.isCancelled());

        SixDeliveryFixtures.waiting(d, SixFileKind.INSTRUMENT, 1L, 10L, null, 11L);
        assertFalse(d.isFinishing());                                       // row not taken
        assertFalse(d.hasPendingOrRunning());
        i.row.batchNodeId = "n";
        assertTrue(d.isFinishing());

        SixDeliveryFixtures.running(d, SixFileKind.OPTIONS, 31L);
        assertTrue(d.hasPendingOrRunning());
        SixDeliveryFixtures.leftInput(i);
        assertTrue(d.isCancelled());
        assertEquals("SIX delivery " + K + " " + Arrays.asList(i, d.file(SixFileKind.OPTIONS)), d.toString());
    }
}
