package com.bnpp.regliss.scheduler;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// TODO: re-add project imports for: GenerateVersionJob, GenerateConcatenatedListJob, BatchExportService,
// BatchManagementService, ReglissRequestContext, BatchExportRepository, GeneratedFileService, UserRepository,
// SixFilterExtractExport, BatchExport, BatchExportParams, BatchExportType, Version, User

@RunWith(MockitoJUnitRunner.Silent.class)
public class BatchExportPollerTest {

    @InjectMocks
    private BatchExportPoller poller;

    @Mock
    private GenerateVersionJob generateVersionJob;
    @Mock
    private GenerateConcatenatedListJob generateConcatenatedListJob;
    @Mock
    private BatchExportService batchExportService;
    @Mock
    private BatchManagementService batchManagementService;
    @Mock
    private ReglissRequestContext requestContext;
    @Mock
    private BatchExportRepository batchExportRepository;
    @Mock
    private GeneratedFileService generatedFileService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private SixFilterExtractExport sixFilterExtractExport;
    @Mock
    private ThreadPoolTaskExecutor executor;

    private final User launcher = mock(User.class);
    private final Version version = mock(Version.class);

    @Before
    public void setUp() {
        when(executor.getMaxPoolSize()).thenReturn(2);
        when(executor.getActiveCount()).thenReturn(0);
        when(batchExportService.tryLockBatchExport(anyLong())).thenReturn(true);
        when(userRepository.findByUsername("bob")).thenReturn(launcher);
        when(batchManagementService.createNewExportBatchExecutionInTx(any(), any())).thenReturn(50L);
        when(batchManagementService.createNewSixRawExportBatchExecutionInTx(any(), any())).thenReturn(60L);
    }

    /** A request whose export type is returned by successive calls (while check, local variable, switch, error text). */
    private BatchExport export(long id, BatchExportType first, BatchExportType... next) {
        BatchExportParams params = mock(BatchExportParams.class);
        when(params.getExportType()).thenReturn(first, next);
        when(params.getVersionOpt()).thenReturn(Optional.of(version));
        when(params.getLauncherUsername()).thenReturn("bob");
        when(params.getGeneratedFileIds()).thenReturn(Arrays.asList(7L, 8L));
        BatchExport export = mock(BatchExport.class);
        when(export.getId()).thenReturn(id);
        when(export.getExportParams()).thenReturn(params);
        return export;
    }

    private void queue(BatchExport... exports) {
        List<BatchExport> next = Arrays.asList(exports);
        BatchExport[] rest = new BatchExport[next.size()];
        for (int i = 1; i < next.size(); i++) {
            rest[i - 1] = next.get(i);
        }
        when(batchExportRepository.getOrderedBatchExportRecords()).thenReturn(next.get(0), rest);
    }

    @Test
    public void everyTypeIsGenerated_jobsFinished() {
        BatchExport version1 = export(1L, BatchExportType.VERSION_GENERATED_FILE);
        BatchExport concat = export(2L, BatchExportType.CONCATENATED_LIST_GENERATED_FILE);
        BatchExport ams = export(3L, BatchExportType.ADD_MOD_SUP_FILE);
        BatchExport amsAudit = export(4L, BatchExportType.ADD_MOD_SUP_AUDIT_TRAIL_FILE);
        BatchExport six = export(5L, BatchExportType.SIX_FILTERED_FILE_GENERATION);
        BatchExport sixRegen = export(6L, BatchExportType.SIX_FILTERED_FILE_REGENERATION);
        queue(version1, concat, ams, amsAudit, six, sixRegen, null);

        poller.generateExportFiles();

        verify(generateVersionJob).generateVersionFiles(version1);
        verify(generateConcatenatedListJob).generateConcatenatedListFiles(concat);
        verify(generateVersionJob).generateAddModSupFile(ams);
        verify(generateVersionJob).generateAddModSupAuditTrailFile(amsAudit);
        verify(sixFilterExtractExport).filteringSixRawFiles(six);
        verify(sixFilterExtractExport).filteringSixRawFiles(sixRegen);
        verify(version1).setBatchExecutionId(50L);
        verify(six).setBatchExecutionId(60L);
        verify(requestContext, times(6)).setCurrentUser(launcher);
        verify(requestContext, times(6)).setRequestTime(any(LocalDateTime.class));
        verify(batchManagementService, times(4)).markBatchExecutionFinishedBySys(50L);
        verify(batchManagementService, never()).markBatchExecutionFinishedBySys(60L);   // SIX: closed by the XML step
    }

    @Test
    public void noFreeThread_nothingTaken() {
        when(executor.getActiveCount()).thenReturn(2);
        queue(export(1L, BatchExportType.VERSION_GENERATED_FILE));
        poller.generateExportFiles();
        verify(batchExportService, never()).tryLockBatchExport(anyLong());
    }

    @Test
    public void confidenceRowStopsTheLoop_andLockedByOtherServerSkipped() {
        BatchExport lockedElsewhere = export(1L, BatchExportType.VERSION_GENERATED_FILE);
        when(batchExportService.tryLockBatchExport(1L)).thenReturn(false);
        queue(lockedElsewhere, export(2L, BatchExportType.SIX_CONFIDENCE_VALUES));
        poller.generateExportFiles();
        verify(generateVersionJob, never()).generateVersionFiles(any());
        verify(batchExportService, never()).tryLockBatchExport(2L);
    }

    @Test
    public void errors_markedOnTheGeneratedFiles() {
        BatchExport ams = export(3L, BatchExportType.ADD_MOD_SUP_FILE);
        BatchExport amsAudit = export(4L, BatchExportType.ADD_MOD_SUP_AUDIT_TRAIL_FILE);
        BatchExport version1 = export(1L, BatchExportType.VERSION_GENERATED_FILE);
        BatchExport six = export(5L, BatchExportType.SIX_FILTERED_FILE_GENERATION);
        BatchExport sixRegen = export(6L, BatchExportType.SIX_FILTERED_FILE_REGENERATION);
        RuntimeException boom = new IllegalStateException("disk");
        doThrow(boom).when(generateVersionJob).generateAddModSupFile(ams);
        doThrow(boom).when(generateVersionJob).generateAddModSupAuditTrailFile(amsAudit);
        doThrow(boom).when(generateVersionJob).generateVersionFiles(version1);
        doThrow(boom).when(sixFilterExtractExport).filteringSixRawFiles(any());
        queue(ams, amsAudit, version1, six, sixRegen, null);

        poller.generateExportFiles();

        verify(generatedFileService).updateAddModSupOnErrorInTx(7L, boom);
        verify(generatedFileService).updateAddModSupAuditTrailOnErrorInTx(7L, boom);
        verify(generatedFileService).updateGeneratedFileStatusOnErrorInTx(Arrays.asList(7L, 8L), boom);
        verify(batchManagementService, never()).markBatchExecutionFinishedBySys(60L);
    }

    /** The type read by the switch is not a generation type (changed meanwhile): rejected as not valid. */
    @Test
    public void unknownTypeAtGeneration_rejected() {
        BatchExport odd = export(1L, BatchExportType.VERSION_GENERATED_FILE, BatchExportType.VERSION_GENERATED_FILE,
                BatchExportType.SIX_CONFIDENCE_VALUES);
        queue(odd, null);
        poller.generateExportFiles();
        verify(generatedFileService).updateGeneratedFileStatusOnErrorInTx(any(), any(IllegalArgumentException.class));
    }

    @Test
    public void noJob_orFinishError_ignored() {
        when(batchManagementService.createNewExportBatchExecutionInTx(any(), any())).thenReturn(null, 51L);
        doThrow(new IllegalStateException("db")).when(batchManagementService).markBatchExecutionFinishedBySys(51L);
        queue(export(1L, BatchExportType.VERSION_GENERATED_FILE), export(2L, BatchExportType.VERSION_GENERATED_FILE), null);
        poller.generateExportFiles();
        verify(batchManagementService, never()).updateBatchExecutionCompletedProgress(any());
        verify(batchManagementService).markBatchExecutionFinishedBySys(51L);
    }

    @Test
    public void withoutVersion() {
        BatchExport noVersion = export(1L, BatchExportType.CONCATENATED_LIST_GENERATED_FILE);
        when(noVersion.getExportParams().getVersionOpt()).thenReturn(Optional.empty());
        queue(noVersion, null);
        poller.generateExportFiles();
        verify(batchManagementService).createNewExportBatchExecutionInTx(noVersion, null);
        verify(generatedFileService, never()).updateGeneratedFileStatusOnErrorInTx(any(), any());
        verify(generatedFileService, never()).updateAddModSupOnErrorInTx(anyLong(), any());
        verify(batchManagementService).updateBatchExecutionCompletedProgress(50L);
        verify(requestContext).setCurrentUser(launcher);
        verify(generateConcatenatedListJob).generateConcatenatedListFiles(noVersion);
        verify(generateVersionJob, never()).generateVersionFiles(any());
    }
}
