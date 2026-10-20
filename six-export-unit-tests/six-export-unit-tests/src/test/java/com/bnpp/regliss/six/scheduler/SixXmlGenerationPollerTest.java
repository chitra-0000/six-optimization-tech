package com.bnpp.regliss.six.scheduler;

import com.bnpp.regliss.importer.six.service.SixPollerRowFixtures;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import javax.persistence.EntityManager;
import java.io.IOException;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// TODO: re-add project imports for: SixFilteredStore, ReglissListRepository, ListTypeBuilder, SixXmlGenerationService,
// FilteredInstrumentFileRepository, FilteredStructureFileRepository, BatchProgressService, BatchManagementService, EmailService,
// BatchJobExecutionRepository, BatchJobExecution, SixExportRunGuard, SixIsinMergeService, SixIsinMergeContent, ReglissList,
// ImportFileType, ListType, FilteredFileBundle, FilteredInstrumentFile, FilteredStructuredFile, FilteredSixTarget,
// SixFilteredPoller, SixPollerRowFixtures

@RunWith(MockitoJUnitRunner.Silent.class)
public class SixXmlGenerationPollerTest {

    private static final LocalDateTime NOW = LocalDateTime.now();
    private static final String GEN = "GENERATION";
    private static final String REGEN = "REGENERATION";

    @InjectMocks
    private SixXmlGenerationPoller poller;

    @Mock
    private SixFilteredStore sixFilteredStore;
    @Mock
    private ReglissListRepository reglissListRepository;
    @Mock
    private ListTypeBuilder listTypeBuilder;
    @Mock
    private SixXmlGenerationService sixXmlGenerationService;
    @Mock
    private FilteredInstrumentFileRepository filteredInstrumentFileRepository;
    @Mock
    private FilteredStructureFileRepository filteredStructureFileRepository;
    @Mock
    private BatchProgressService batchProgressService;
    @Mock
    private BatchManagementService batchManagementService;
    @Mock
    private EmailService emailService;
    @Mock
    private BatchJobExecutionRepository batchJobExecutionRepository;
    @Mock
    private SixExportRunGuard sixExportRunGuard;
    @Mock
    private SixIsinMergeService sixIsinMergeService;
    @Mock
    private EntityManager entityManager;

    private ReglissList list951;

    @Before
    public void setUp() throws IOException {
        ReflectionTestUtils.setField(poller, "allowSixFilesToIntegrate", "instr, ,struct");
        ReflectionTestUtils.setField(poller, "buildKeepAliveSeconds", 60L);
        ReflectionTestUtils.setField(poller, "buildKeepAliveMaxMinutes", 120L);
        list951 = list("951");
        lists(list951);
        when(reglissListRepository.findByListRef(anyList())).thenReturn(Collections.emptyList());
        when(sixFilteredStore.readyRows(anyString())).thenReturn(Collections.emptyList());
        when(sixFilteredStore.claimPollerRows(anyString(), anySet())).thenReturn(SixPollerRowFixtures.claim(false));
        when(sixFilteredStore.rowsInStatus(any())).thenReturn(Collections.emptyList());
        when(sixExportRunGuard.deliveryOf(anyLong())).thenAnswer(inv -> "D" + (((Long) inv.getArgument(0)) / 10));
        when(sixExportRunGuard.listStop(anyLong(), any(), anyString())).thenReturn(Optional.empty());
        when(sixExportRunGuard.describe(any())).thenReturn("it failed");
        when(sixIsinMergeService.load(any(), any(), any(), anySet())).thenReturn(SixIsinMergeContent.none());
        when(listTypeBuilder.generationOfListTypes(anyMap(), anyString(), any())).thenReturn(new ListType());
        when(sixXmlGenerationService.createHeldFileOrThrow(any(), anySet(), anyString(), anyString(), any()))
                .thenReturn(Paths.get("/hold/CONVERTER-951.xml"));
        when(sixFilteredStore.jobLists(anyLong())).thenReturn(SixPollerRowFixtures.jobLists("BUILT"));
        when(batchJobExecutionRepository.findById(anyLong())).thenReturn(Optional.empty());
    }

    private static ReglissList list(String reference) {
        ReglissList l = mock(ReglissList.class);
        when(l.getReference()).thenReturn(reference);
        return l;
    }

    private void lists(ReglissList... lists) {
        when(reglissListRepository.findByDJFormatNotDeleted(ImportFileType.SIX_MAIN_FILE)).thenReturn(Arrays.asList(lists));
    }

    private static SixFilteredStore.PollerRow row(long id, String type, Long version, Long job, String list, String status,
                                                  LocalDateTime updated, String reason) {
        return SixPollerRowFixtures.row(id, type, 1L, version, job, list, status, updated, reason);
    }

    private void claimed(SixFilteredStore.PollerRow... rows) {
        SixFilteredStore.PollerClaim claim = SixPollerRowFixtures.claim(true, rows);
        when(sixFilteredStore.claimPollerRows(eq("951"), anySet())).thenReturn(claim);
    }

    private static BatchJobExecution job(Object percent, LocalDateTime lastUpdate) {
        BatchJobExecution job = mock(BatchJobExecution.class);
        when(job.getPercent()).thenReturn((Float) percent);
        when(job.getLastUpdateDate()).thenReturn(lastUpdate);
        return job;
    }

    // ------------------------------------------------------------------ build of one list

    @Test
    public void build_heldFile_progress() {
        claimed(row(1, "INSTR", 51L, 7L, "951", "BUILDING", NOW, GEN), row(2, "STRUCT", 52L, 8L, "951", "BUILDING", NOW, GEN),
                row(3, "INSTR", 41L, 6L, "951", "BUILDING", NOW, GEN));             // older delivery: superseded
        when(filteredInstrumentFileRepository.findLatestVersionIdByListRef("951")).thenReturn(51L);
        FilteredInstrumentFile instrument = new FilteredInstrumentFile();
        instrument.setIsin("XS1");
        instrument.setChValor("1");
        when(filteredInstrumentFileRepository.findByVersionAndListRefWithTargets(51L, "951")).thenReturn(Collections.singletonList(instrument));
        when(filteredStructureFileRepository.findLatestVersionIdByListRef("951")).thenReturn(null);
        BatchJobExecution job7 = job(42.5f, NOW);
        when(batchJobExecutionRepository.findById(7L)).thenReturn(Optional.of(job7));
        when(sixFilteredStore.jobLists(7L)).thenReturn(SixPollerRowFixtures.jobLists("BUILDING", "READY"));     // 2 lists
        doThrow(new IllegalStateException("db")).when(sixFilteredStore).jobLists(8L);

        poller.generateXmlFiles();

        verify(sixFilteredStore).markBuildFailed(Collections.singletonList(3L));
        verify(sixXmlGenerationService).createHeldFileOrThrow(any(ListType.class), eq(new LinkedHashSet<>(Arrays.asList(7L, 8L))),
                eq("D5_GENERATION"), eq("951"), eq(51L));
        verify(sixFilteredStore).markBuilt(Arrays.asList(1L, 2L));
        verify(sixIsinMergeService).startRun();
        verify(sixIsinMergeService).load(eq(list951), any(SixIsinMergeService.Scope.class), any(SixIsinMergeService.Scope.class),
                eq(Collections.singleton("XS1")));
        verify(batchProgressService).incrementExportBatchForSix(7L, 9);                 // 42.5 + 45 * 0.2 = 51.5 -> 51 - 42
        verify(entityManager).clear();
    }

    @Test
    public void build_keepAliveTimerRefreshesTheBuild() throws Exception {
        ReflectionTestUtils.setField(poller, "buildKeepAliveSeconds", 1L);
        claimed(row(1, "INSTR", 51L, 7L, "951", "BUILDING", NOW, GEN));
        CountDownLatch timerTick = new CountDownLatch(1);
        doAnswer(inv -> {
            if (Thread.currentThread().getName().startsWith("six-keepalive-")) {
                timerTick.countDown();
            }
            return null;
        }).when(sixFilteredStore).touchBuilding(anyList());
        when(listTypeBuilder.generationOfListTypes(anyMap(), anyString(), any())).thenAnswer(inv -> {
            assertTrue("timer refreshed the build", timerTick.await(10, TimeUnit.SECONDS));
            return new ListType();
        });
        poller.generateXmlFiles();
        verify(sixFilteredStore).markBuilt(Collections.singletonList(1L));
    }

    @Test
    public void build_progressOfAJobWithoutPercent_andRefreshErrorsIgnored() {
        claimed(row(1, "INSTR", 51L, 7L, "951", "BUILDING", NOW, GEN));
        BatchJobExecution job7 = job(null, NOW);
        when(batchJobExecutionRepository.findById(7L)).thenReturn(Optional.of(job7));
        when(sixFilteredStore.jobLists(7L)).thenReturn(SixPollerRowFixtures.jobLists());                  // no list: share 0
        doThrow(new IllegalStateException("db")).when(sixFilteredStore).touchBuilding(anyList());
        doThrow(new IllegalStateException("db")).when(batchProgressService).incrementExportBatchForSix(7L, 0);
        poller.generateXmlFiles();
        verify(sixFilteredStore).markBuilt(Collections.singletonList(1L));
    }

    @Test
    public void build_regenerationRows_noJob_noInstrument() {
        claimed(row(1, "STRUCT", 52L, null, "951", "BUILDING", NOW, REGEN), row(2, null, null, null, "951", "BUILDING", NOW, REGEN));
        when(filteredInstrumentFileRepository.findLatestVersionIdByListRef("951")).thenReturn(null);
        when(filteredStructureFileRepository.findLatestVersionIdByListRef("951")).thenReturn(52L);
        when(filteredStructureFileRepository.findByVersionAndListRefWithTargets(52L, "951")).thenReturn(new ArrayList<>());
        poller.generateXmlFiles();
        verify(sixXmlGenerationService).createHeldFileOrThrow(any(), eq(Collections.emptySet()), eq("D5_REGENERATION"), eq("951"), isNull());
        verify(sixIsinMergeService).load(eq(list951), isNull(), any(SixIsinMergeService.Scope.class), anySet());
        verify(batchProgressService, never()).incrementExportBatchForSix(anyLong(), anyInt());
    }

    @Test
    public void build_claimWithoutRows() {
        claimed();
        poller.generateXmlFiles();
        verify(sixXmlGenerationService).createHeldFileOrThrow(any(), anySet(), eq("unknown_GENERATION"), eq("951"), isNull());
    }

    @Test
    public void build_listFailedMeanwhile_notBuilt_butDeliveryStopStillBuilt() {
        claimed(row(1, "INSTR", 51L, 7L, "951", "BUILDING", NOW, GEN));
        SixFilteredPoller failed = new SixFilteredPoller("INSTR", SixExportRunGuard.FAILED, NOW, 1L, 51L, "951", 7L);
        when(sixExportRunGuard.listStop(51L, 7L, "951")).thenReturn(Optional.of(failed));
        poller.generateXmlFiles();
        verify(sixFilteredStore).markBuildFailed(Collections.singletonList(1L));
        verify(sixXmlGenerationService, never()).createHeldFileOrThrow(any(), anySet(), anyString(), anyString(), any());

        SixFilteredPoller all = new SixFilteredPoller("INSTR", SixExportRunGuard.FAILED_ALL, NOW, 1L, 51L, null, 7L);
        when(sixExportRunGuard.listStop(51L, 7L, "951")).thenReturn(Optional.of(all));
        poller.generateXmlFiles();
        verify(sixFilteredStore).markBuilt(Collections.singletonList(1L));
    }

    @Test
    public void build_errorInAStage_listFailsWithMail() {
        claimed(row(1, "INSTR", 51L, 7L, "951", "BUILDING", NOW, GEN));
        when(filteredInstrumentFileRepository.findLatestVersionIdByListRef("951")).thenThrow(new IllegalStateException("ORA-1"));
        poller.generateXmlFiles();
        verify(sixFilteredStore).markBuildFailed(Collections.singletonList(1L));
        ArgumentCaptor<String> mail = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendEmailDjImportGeneralError(eq(list951), mail.capture());
        assertTrue(mail.getValue(), mail.getValue().startsWith("Error occurred during generating the XML file of list 951 due to database error."));
    }

    @Test
    public void build_errorOfAListWithoutJob() {
        claimed(row(1, "INSTR", 51L, null, "951", "BUILDING", NOW, GEN));
        when(listTypeBuilder.generationOfListTypes(anyMap(), anyString(), any())).thenThrow(new IllegalStateException("x"));
        poller.generateXmlFiles();
        ArgumentCaptor<String> mail = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendEmailDjImportGeneralError(eq(list951), mail.capture());
        assertTrue(mail.getValue(), mail.getValue().endsWith("Technical details: server log."));
    }

    @Test
    public void build_errorWhileWriting_andFailureNotRecorded() {
        claimed(row(1, "INSTR", 51L, 7L, "951", "BUILDING", NOW, GEN));
        when(sixXmlGenerationService.createHeldFileOrThrow(any(), anySet(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("disk full"));
        doThrow(new IllegalStateException("db")).when(sixFilteredStore).markBuildFailed(anyList());
        doThrow(new IllegalStateException("smtp")).when(emailService).sendEmailDjImportGeneralError(any(), anyString());
        poller.generateXmlFiles();
        verify(sixFilteredStore, never()).markBuilt(anyList());
    }

    @Test
    public void build_outOfMemory_onlyThisListFails() {
        claimed(row(1, "INSTR", 51L, 7L, "951", "BUILDING", NOW, GEN));
        when(listTypeBuilder.generationOfListTypes(anyMap(), anyString(), any())).thenThrow(new OutOfMemoryError("heap"));
        poller.generateXmlFiles();
        ArgumentCaptor<String> mail = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendEmailDjImportGeneralError(eq(list951), mail.capture());
        assertTrue(mail.getValue(), mail.getValue().contains("not enough memory"));
    }

    @Test
    public void notClaimed_waitingJobsKeptAliveWhileTheOtherFileIsFiltered() {
        BatchJobExecution alive = job(null, NOW);
        BatchJobExecution old = job(null, NOW.minusHours(1));
        BatchJobExecution neverUpdated = job(null, null);
        when(batchJobExecutionRepository.findById(20L)).thenReturn(Optional.of(old));
        when(batchJobExecutionRepository.findById(21L)).thenReturn(Optional.of(neverUpdated));
        when(batchJobExecutionRepository.findById(22L)).thenReturn(Optional.of(alive));
        when(sixFilteredStore.jobsStillFiltering("951", GEN)).thenReturn(Arrays.asList(19L, 20L, 21L, 22L));
        when(sixFilteredStore.jobsStillFiltering("951", REGEN)).thenReturn(Collections.singletonList(20L));
        SixFilteredStore.PollerClaim waiting = SixPollerRowFixtures.claim(false,
                row(1, "INSTR", 51L, 7L, "951", "READY", NOW, GEN), row(2, "INSTR", 61L, 9L, "951", "READY", NOW, REGEN));
        when(sixFilteredStore.claimPollerRows(eq("951"), anySet())).thenReturn(waiting);

        poller.generateXmlFiles();

        verify(batchProgressService).incrementExportBatchForSix(7L, 0);
        verify(batchProgressService, never()).incrementExportBatchForSix(9L, 0);
        verify(sixXmlGenerationService, never()).createHeldFileOrThrow(any(), anySet(), anyString(), anyString(), any());
    }

    // ------------------------------------------------------------------ rows of failed lists

    @Test
    public void readyRowsOfFailedLists_dropped() {
        // GENERATION: list failed -> dropped (its filtered rows removed, or kept when in use)
        SixFilteredStore.PollerRow failedInstr = row(1, "INSTR", 51L, 7L, "951", "READY", NOW, GEN);
        SixFilteredStore.PollerRow noVersion = row(2, "STRUCT", null, 7L, "951", "READY", NOW, GEN);
        // REGENERATION: delivery stopped, list incomplete, nothing left to filter -> dropped
        SixFilteredStore.PollerRow stoppedRegen = row(3, "INSTR", 61L, 9L, "951", "READY", NOW, REGEN);
        when(sixFilteredStore.readyRows("951")).thenReturn(Arrays.asList(failedInstr, noVersion, stoppedRegen));
        when(sixExportRunGuard.listStop(51L, 7L, "951")).thenReturn(Optional.of(
                new SixFilteredPoller("INSTR", SixExportRunGuard.FAILED, NOW, 1L, 51L, "951", 7L)));
        when(sixExportRunGuard.listStop(61L, 9L, "951")).thenReturn(Optional.of(
                new SixFilteredPoller("INSTR", SixExportRunGuard.FAILED_ALL, NOW, 1L, 61L, null, 9L)));
        when(sixFilteredStore.jobsStillFiltering("951", REGEN)).thenReturn(Collections.emptyList());
        when(sixFilteredStore.removeFilteredRowsOfDroppedRow(failedInstr)).thenReturn(3);
        when(sixFilteredStore.removeFilteredRowsOfDroppedRow(stoppedRegen)).thenReturn(-1);

        poller.generateXmlFiles();

        verify(sixFilteredStore).dropReady(Collections.singletonList(1L));
        verify(sixFilteredStore).dropReady(Collections.singletonList(3L));
    }

    @Test
    public void readyRowsOfAStoppedDelivery_kept_whenCompleteOrStillFiltered() {
        SixFilteredStore.PollerRow instr = row(1, "INSTR", 51L, 7L, "951", "READY", NOW, GEN);
        SixFilteredStore.PollerRow struct = row(2, "STRUCT", 52L, 8L, "951", "READY", NOW, GEN);
        SixFilteredStore.PollerRow regen = row(3, "INSTR", 61L, 9L, "951", "READY", NOW, REGEN);
        when(sixFilteredStore.readyRows("951")).thenReturn(Arrays.asList(instr, struct, regen));
        SixFilteredPoller all = new SixFilteredPoller("INSTR", SixExportRunGuard.FAILED_ALL, NOW, 1L, 51L, null, 7L);
        when(sixExportRunGuard.listStop(anyLong(), any(), eq("951"))).thenReturn(Optional.of(all));
        when(sixFilteredStore.jobsStillFiltering("951", REGEN)).thenReturn(Collections.singletonList(10L));

        poller.generateXmlFiles();

        verify(sixFilteredStore, never()).dropReady(anyList());
    }

    @Test
    public void removeFilteredRowsError_ignored() {
        SixFilteredStore.PollerRow failed = row(1, "INSTR", 51L, 7L, "951", "READY", NOW, GEN);
        when(sixFilteredStore.readyRows("951")).thenReturn(Collections.singletonList(failed));
        when(sixExportRunGuard.listStop(51L, 7L, "951")).thenReturn(Optional.of(
                new SixFilteredPoller("INSTR", SixExportRunGuard.FAILED, NOW, 1L, 51L, "951", 7L)));
        when(sixFilteredStore.removeFilteredRowsOfDroppedRow(failed)).thenThrow(new IllegalStateException("db"));
        poller.generateXmlFiles();
        verify(sixFilteredStore).dropReady(Collections.singletonList(1L));
    }

    // ------------------------------------------------------------------ run loop

    @Test
    public void errorsOutsideTheStages_nextList_nextRun() {
        ReglissList list235 = list("235");
        lists(list951, list235);
        ReglissList current235 = list("235");
        List<ReglissList> other = Arrays.asList(list("9999"), current235);
        when(reglissListRepository.findByListRef(Collections.singletonList("235"))).thenReturn(other);
        when(sixFilteredStore.claimPollerRows(eq("951"), anySet())).thenThrow(new IllegalStateException("db"));
        SixFilteredStore.PollerClaim claim235 = SixPollerRowFixtures.claim(true, row(1, "INSTR", 51L, 7L, "235", "BUILDING", NOW, GEN));
        when(sixFilteredStore.claimPollerRows(eq("235"), anySet())).thenReturn(claim235);
        doThrow(new IllegalStateException("closed")).when(entityManager).clear();
        when(sixFilteredStore.rowsInStatus(SixFilteredStore.BUILT)).thenThrow(new IllegalStateException("db"));

        poller.generateXmlFiles();

        verify(sixIsinMergeService).load(eq(current235), any(), any(), anySet());     // the list read again
        verify(entityManager, times(2)).clear();
    }

    // ------------------------------------------------------------------ release of a delivery

    @SuppressWarnings("unchecked")
    private void releaseRuns(List<SixFilteredStore.PollerRow> locked) {
        when(sixFilteredStore.releaseInTx(anyList(), any())).thenAnswer(inv ->
                ((Function<List<SixFilteredStore.PollerRow>, Object>) inv.getArgument(1)).apply(locked));
    }

    private void built(SixFilteredStore.PollerRow... rows) {
        when(sixFilteredStore.rowsInStatus(SixFilteredStore.BUILT)).thenReturn(Arrays.asList(rows));
    }

    private void open(SixFilteredStore.PollerRow... rows) {
        when(sixFilteredStore.rowsInStatus(SixFilteredStore.PENDING, SixFilteredStore.READY, SixFilteredStore.BUILDING))
                .thenReturn(Arrays.asList(rows));
    }

    @Test
    public void release_everyFileIntoDjIn_jobsFinished() throws IOException {
        ReglissList list235 = list("235");
        lists(list951, list235);
        SixFilteredStore.PollerRow b951 = row(1, "INSTR", 51L, 7L, "951", "BUILT", NOW, GEN);
        SixFilteredStore.PollerRow b235 = row(2, "INSTR", 51L, 7L, "235", "BUILT", NOW, GEN);
        SixFilteredStore.PollerRow b952 = row(3, "INSTR", 51L, 8L, "952", "BUILT", NOW, GEN);   // list not active any more
        SixFilteredStore.PollerRow b953 = row(4, "INSTR", 51L, 8L, "953", "BUILT", NOW, GEN);
        built(b951, b235, b952, b953);
        // open rows of the delivery that are not in progress: dropped
        SixFilteredStore.PollerRow deadPending = row(5, "STRUCT", 52L, 9L, "951", "PENDING", NOW, GEN);
        SixFilteredStore.PollerRow noJob = row(6, "STRUCT", 52L, null, "235", "PENDING", NOW, GEN);
        SixFilteredStore.PollerRow oldBuilding = row(7, "STRUCT", 52L, 9L, "235", "BUILDING", NOW.minusHours(1), GEN);
        SixFilteredStore.PollerRow neverTouched = row(8, "STRUCT", 52L, 9L, "235", "BUILDING", null, GEN);
        SixFilteredStore.PollerRow readyOfInactiveList = row(9, "STRUCT", 52L, 9L, "999", "READY", NOW, GEN);
        SixFilteredStore.PollerRow readyIncomplete = row(10, "STRUCT", 52L, 9L, "951", "READY", NOW, GEN);
        SixFilteredStore.PollerRow otherStatus = row(11, "STRUCT", 52L, 9L, "951", "DROPPED", NOW, GEN);
        SixFilteredStore.PollerRow otherDelivery = row(12, "STRUCT", 72L, 9L, "951", "PENDING", NOW, GEN);
        open(deadPending, noJob, oldBuilding, neverTouched, readyOfInactiveList, readyIncomplete, otherStatus, otherDelivery);
        releaseRuns(Arrays.asList(b951, b235, b952, b953));
        when(sixXmlGenerationService.releaseHeldFiles("D5_GENERATION", "951")).thenReturn(Collections.singletonList("CONVERTER-951.xml"));
        when(sixXmlGenerationService.releaseHeldFiles("D5_GENERATION", "235")).thenReturn(Collections.emptyList());
        when(sixXmlGenerationService.releaseHeldFiles("D5_GENERATION", "952")).thenThrow(new IOException("disk"));
        when(sixXmlGenerationService.releaseHeldFiles("D5_GENERATION", "953")).thenThrow(new IllegalStateException("x"));
        when(sixFilteredStore.jobLists(7L)).thenReturn(SixPollerRowFixtures.jobLists("DONE", "DONE"));
        when(sixFilteredStore.jobLists(8L)).thenReturn(SixPollerRowFixtures.jobLists("DONE", "DROPPED"));
        doThrow(new IllegalStateException("db")).when(sixFilteredStore).jobLists(9L);

        poller.generateXmlFiles();

        verify(sixFilteredStore).dropUnfinished(Arrays.asList(5L, 6L, 7L, 8L, 9L, 10L, 11L));
        verify(sixFilteredStore).markReleased(Collections.singletonList(1L));
        verify(sixFilteredStore).markReleased(Collections.singletonList(2L));
        verify(sixFilteredStore).markReleaseFailed(Collections.singletonList(3L));
        verify(sixFilteredStore).markReleaseFailed(Collections.singletonList(4L));
        verify(batchManagementService).markBatchExecutionFinishedBySys(7L);
        verify(batchManagementService).updateBatchExecutionCompletedProgress(7L);
        verify(batchManagementService, never()).markBatchExecutionFinishedBySys(8L);
        verify(emailService, never()).sendEmailDjImportGeneralError(any(), anyString());   // 952 / 953 not active: no list to mail
        verify(sixFilteredStore, times(7)).removeFilteredRowsOfDroppedRow(any());
    }

    @Test
    public void release_fileNotMoved_mailToTheList() throws IOException {
        lists(list951, list("951"));                                                   // same reference twice: the first one is used
        SixFilteredStore.PollerRow b951 = row(1, "INSTR", 51L, 7L, "951", "BUILT", NOW, GEN);
        built(b951);
        releaseRuns(Collections.singletonList(b951));
        when(sixXmlGenerationService.releaseHeldFiles("D5_GENERATION", "951")).thenThrow(new IOException("disk"));
        doThrow(new IllegalStateException("smtp")).when(emailService).sendEmailDjImportGeneralError(any(), anyString());
        poller.generateXmlFiles();
        verify(emailService).sendEmailDjImportGeneralError(eq(list951), anyString());
        verify(sixFilteredStore, never()).dropUnfinished(anyList());
    }

    @Test
    public void release_failedFile_withoutJob() throws IOException {
        SixFilteredStore.PollerRow b951 = row(1, "INSTR", 51L, null, "951", "BUILT", NOW, GEN);
        built(b951);
        releaseRuns(Collections.singletonList(b951));
        when(sixXmlGenerationService.releaseHeldFiles("D5_GENERATION", "951")).thenThrow(new IOException("disk"));
        poller.generateXmlFiles();
        verify(emailService).sendEmailDjImportGeneralError(eq(list951), anyString());
    }

    @Test
    public void release_waitsForAListInProgress_heldJobsKeptAlive() {
        SixFilteredStore.PollerRow b951 = row(1, "INSTR", 51L, 7L, "951", "BUILT", NOW, GEN);
        SixFilteredStore.PollerRow b951s = row(2, "STRUCT", 52L, 8L, "951", "BUILT", NOW, GEN);
        built(b951, b951s);
        SixFilteredStore.PollerRow pendingAlive = row(3, "INSTR", 51L, 8L, "235", "PENDING", NOW, GEN);
        open(pendingAlive);
        ReglissList list235 = list("235");
        lists(list951, list235);
        when(sixExportRunGuard.isJobWorking(8L)).thenReturn(true);

        poller.generateXmlFiles();

        verify(batchProgressService).incrementExportBatchForSix(7L, 0);                 // held, idle
        verify(batchProgressService, never()).incrementExportBatchForSix(8L, 0);        // still filtering: refreshes itself
        verify(sixFilteredStore, never()).releaseInTx(anyList(), any());
    }

    @Test
    public void release_readyListsStillToBuild_areInProgress() {
        ReglissList list235 = list("235");
        lists(list951, list235);
        built(row(1, "INSTR", 51L, 7L, "951", "BUILT", NOW, GEN));
        // 235 complete (both types READY): built in this run or the next one
        open(row(2, "INSTR", 51L, 7L, "235", "READY", NOW, GEN), row(3, "STRUCT", 52L, 8L, "235", "READY", NOW, GEN));
        poller.generateXmlFiles();
        verify(sixFilteredStore, never()).releaseInTx(anyList(), any());

        // 235 waits for STRUCT still filtered by an alive job
        open(row(2, "INSTR", 51L, 7L, "235", "READY", NOW, GEN), row(4, "STRUCT", 52L, 8L, "235", "PENDING", NOW, GEN),
                row(5, "STRUCT", 52L, 9L, "999", "PENDING", NOW, GEN));
        when(sixExportRunGuard.isJobWorking(8L)).thenReturn(true);
        poller.generateXmlFiles();
        verify(sixFilteredStore, never()).releaseInTx(anyList(), any());

        // a list being built now
        open(row(6, "STRUCT", 52L, 8L, "235", "BUILDING", NOW, GEN));
        poller.generateXmlFiles();
        verify(sixFilteredStore, never()).releaseInTx(anyList(), any());
    }

    @Test
    public void release_byTheOtherServer_orAListStartedMeanwhile() {
        SixFilteredStore.PollerRow b951 = row(1, "INSTR", 51L, 7L, "951", "BUILT", NOW, GEN);
        built(b951);
        when(sixFilteredStore.releaseInTx(anyList(), any())).thenReturn(null);
        poller.generateXmlFiles();
        verify(sixFilteredStore, never()).markReleased(anyList());

        // in the release transaction a list of the delivery is now in progress
        releaseRuns(Collections.singletonList(b951));
        List<SixFilteredStore.PollerRow> nowBuilding = Collections.singletonList(row(2, "STRUCT", 52L, 8L, "951", "BUILDING", NOW, GEN));
        when(sixFilteredStore.rowsInStatus(SixFilteredStore.PENDING, SixFilteredStore.READY, SixFilteredStore.BUILDING))
                .thenReturn(Collections.emptyList(), nowBuilding);
        poller.generateXmlFiles();
        verify(sixFilteredStore, never()).markReleased(anyList());
    }

    @Test
    public void release_errorOfOneDelivery_otherDeliveriesStillReleased() throws IOException {
        SixFilteredStore.PollerRow d5 = row(1, "INSTR", 51L, 7L, "951", "BUILT", NOW, GEN);
        SixFilteredStore.PollerRow d6 = row(2, "INSTR", 61L, 8L, "951", "BUILT", NOW, REGEN);
        built(d5, d6);
        when(sixFilteredStore.releaseInTx(eq(Collections.singletonList(1L)), any())).thenThrow(new IllegalStateException("db"));
        releaseRunsFor(Collections.singletonList(2L), Collections.singletonList(d6));
        when(sixXmlGenerationService.releaseHeldFiles("D6_REGENERATION", "951")).thenReturn(Collections.singletonList("f.xml"));
        poller.generateXmlFiles();
        verify(sixFilteredStore).markReleased(Collections.singletonList(2L));
    }

    @SuppressWarnings("unchecked")
    private void releaseRunsFor(List<Long> ids, List<SixFilteredStore.PollerRow> locked) {
        when(sixFilteredStore.releaseInTx(eq(ids), any())).thenAnswer(inv ->
                ((Function<List<SixFilteredStore.PollerRow>, Object>) inv.getArgument(1)).apply(locked));
    }

    // ------------------------------------------------------------------ generic rules

    private static FilteredInstrumentFile instrument(String isin, String ch, String... sanctioned) {
        FilteredInstrumentFile f = new FilteredInstrumentFile();
        f.setIsin(isin);
        f.setChValor(ch);
        for (String s : sanctioned) {
            FilteredSixTarget t = new FilteredSixTarget();
            t.setSanctioned(s);
            f.getSixTargets().add(t);
        }
        return f;
    }

    private static FilteredStructuredFile structured(String hostIsin, String ch, String... sanctioned) {
        FilteredStructuredFile f = new FilteredStructuredFile();
        f.setHostIsin(hostIsin);
        f.setHostCh(ch);
        for (String s : sanctioned) {
            FilteredSixTarget t = new FilteredSixTarget();
            t.setSanctioned(s);
            f.getSixTargets().add(t);
        }
        return f;
    }

    @Test
    public void genericRules_duplicateBySanction() {
        // A sanctioned: its structured products (HOST_ISIN = A) are removed.
        // B not sanctioned (text "No"): the structured product whose HOST_ISIN is "No" is sanctioned -> instrument B removed.
        // C not sanctioned (text "No - no"): the structured product with HOST_ISIN "No - no" is not sanctioned -> removed, C kept.
        List<FilteredInstrumentFile> instruments = new ArrayList<>(Arrays.asList(instrument("A", "1", "Yes"), instrument("B", "2", "No"),
                instrument("C", "3", "No", "no"), instrument(null, "4", "Yes")));
        List<FilteredStructuredFile> structured = new ArrayList<>(Arrays.asList(structured("A", "s1", "No"), structured("No - no", "s2", "No"),
                structured("No", "s3", "Yes"), structured("Z", "s4", "Yes")));

        FilteredFileBundle bundle = poller.applyGenericFilters(instruments, structured, new FilteredFileBundle(), "951");

        assertEquals(Arrays.asList("A", "C"), bundle.getInstrumentFiles().stream().map(FilteredInstrumentFile::getIsin)
                .collect(Collectors.toList()));
        assertEquals(Arrays.asList("s3", "s4"), bundle.getStructuredFiles().stream().map(FilteredStructuredFile::getHostCh)
                .sorted().collect(Collectors.toList()));
    }

    /** Two instruments with the same SANCTIONED text: the structured product removed for the first is not checked again. */
    @Test
    public void genericRules_structuredAlreadyRemoved() {
        List<FilteredInstrumentFile> instruments = new ArrayList<>(Arrays.asList(instrument("B", "1", "No"), instrument("C", "2", "No")));
        List<FilteredStructuredFile> structured = new ArrayList<>(Collections.singletonList(structured("No", "s1", "No")));
        FilteredFileBundle bundle = poller.applyGenericFilters(instruments, structured, new FilteredFileBundle(), "951");
        assertTrue(bundle.getStructuredFiles().isEmpty());
        assertEquals(2, bundle.getInstrumentFiles().size());
    }

    @Test
    public void genericRules_nothingRemoved() {
        List<FilteredInstrumentFile> instruments = new ArrayList<>(Collections.singletonList(instrument("A", "1", "No")));
        List<FilteredStructuredFile> structured = new ArrayList<>(Collections.singletonList(structured("No", "s1", "Maybe")));
        FilteredFileBundle bundle = poller.applyGenericFilters(instruments, structured, new FilteredFileBundle(), "951");
        assertEquals(1, bundle.getInstrumentFiles().size());
        assertEquals(1, bundle.getStructuredFiles().size());
    }
}
