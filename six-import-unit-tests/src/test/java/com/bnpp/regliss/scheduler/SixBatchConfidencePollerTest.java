package com.bnpp.regliss.scheduler;

import com.bnpp.regliss.importer.six.service.SixConfidenceStore;
import com.bnpp.regliss.importer.six.service.SixDeliveryFixtures;
import com.bnpp.regliss.importer.six.service.SixDeliveryService;
import com.bnpp.regliss.importer.six.service.SixFileKind;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// TODO: re-add project imports for: BatchProgressService, HeartbeatService, ReglissRequestContext, ReferenceService, User

@RunWith(MockitoJUnitRunner.Silent.class)
public class SixBatchConfidencePollerTest {

    private static final long K = 20261004101500L;
    private static final String ME = "me";

    @InjectMocks
    private SixBatchConfidencePoller poller;

    @Mock
    private SixDeliveryService deliveryService;
    @Mock
    private SixConfidenceStore store;
    @Mock
    private BatchProgressService batchProgressService;
    @Mock
    private HeartbeatService heartbeatService;
    @Mock
    private ReglissRequestContext requestContext;
    @Mock
    private ReferenceService referenceService;

    private final User sys = mock(User.class);

    @Before
    public void setUp() {
        when(referenceService.getSysUser()).thenReturn(sys);
        when(deliveryService.currentNodeId()).thenReturn(ME);
    }

    private void found(SixDeliveryService.Delivery first, SixDeliveryService.Delivery... next) {
        @SuppressWarnings("unchecked")
        Optional<SixDeliveryService.Delivery>[] rest = new Optional[next.length];
        for (int i = 0; i < next.length; i++) {
            rest[i] = Optional.ofNullable(next[i]);
        }
        when(deliveryService.findWaitingDelivery()).thenReturn(Optional.ofNullable(first), rest);
    }

    /** Instrument + STRUCT + OPT all waiting at 90%, nothing merged yet. */
    private SixDeliveryService.Delivery readyDelivery() {
        SixDeliveryService.Delivery d = SixDeliveryFixtures.delivery(K);
        SixDeliveryFixtures.waiting(d, SixFileKind.INSTRUMENT, 1L, 10L, null, 11L);
        SixDeliveryFixtures.waiting(d, SixFileKind.STRUCTURED, 2L, 20L, null, 12L);
        SixDeliveryFixtures.waiting(d, SixFileKind.OPTIONS, 3L, 30L, null, 13L);
        return d;
    }

    /** Same delivery after both merges (dependent rows taken), instrument row held by {@code holder}. */
    private SixDeliveryService.Delivery mergedDelivery(String holder) {
        SixDeliveryService.Delivery d = SixDeliveryFixtures.delivery(K);
        SixDeliveryFixtures.waiting(d, SixFileKind.INSTRUMENT, 1L, 10L, holder, 11L);
        SixDeliveryFixtures.waiting(d, SixFileKind.STRUCTURED, 2L, 20L, ME, 12L);
        SixDeliveryFixtures.waiting(d, SixFileKind.OPTIONS, 3L, 30L, "other", 13L);
        return d;
    }

    // ------------------------------------------------------------------ nothing to do / step 1

    @Test
    public void nothingWaiting() {
        found(null);
        poller.generateExportFiles();
        verify(requestContext).setCurrentUser(sys);
        verify(deliveryService, never()).rollbackDelivery(any(), any(), anyString());
    }

    @Test
    public void cancelledDelivery_rolledBack() {
        SixDeliveryService.Delivery d = readyDelivery();
        SixDeliveryFixtures.leftInput(d.members().get(1));
        found(d);
        poller.generateExportFiles();
        verify(deliveryService).rollbackDelivery(eq(K), isNull(), anyString());
        verify(store, never()).mergeConfidenceOnce(anyLong(), any(), anyLong(), anyLong(), anyString());
    }

    @Test
    public void otherFilesStillImporting_waits() {
        SixDeliveryService.Delivery running = SixDeliveryFixtures.delivery(K);
        SixDeliveryFixtures.waiting(running, SixFileKind.INSTRUMENT, 1L, 10L, null, 11L);
        SixDeliveryFixtures.running(running, SixFileKind.STRUCTURED, 12L);
        found(running);
        poller.generateExportFiles();
        verify(deliveryService, never()).rollbackDelivery(any(), any(), anyString());
        verify(store, never()).mergeConfidenceOnce(anyLong(), any(), anyLong(), anyLong(), anyString());
    }

    @Test
    public void noInstrumentFile_rolledBack() {
        SixDeliveryService.Delivery d = SixDeliveryFixtures.delivery(K);
        SixDeliveryFixtures.waiting(d, SixFileKind.STRUCTURED, 2L, 20L, null, 12L);
        found(d);
        poller.generateExportFiles();
        verify(deliveryService).rollbackDelivery(K, null, "no instrument file in the delivery");
    }

    @Test
    public void instrumentWithoutVersion_rolledBack() {
        SixDeliveryService.Delivery d = SixDeliveryFixtures.delivery(K);
        SixDeliveryFixtures.waiting(d, SixFileKind.INSTRUMENT, 1L, null, null, 11L);
        found(d);
        poller.generateExportFiles();
        verify(deliveryService).rollbackDelivery(K, null, "the instrument file has no version");
    }

    // ------------------------------------------------------------------ step 2 + 3

    @Test
    public void fullRun_mergesThenFinishes() {
        SixDeliveryService.Delivery ready = readyDelivery();
        SixDeliveryService.Delivery merged = mergedDelivery(null);
        SixDeliveryService.Delivery taken = mergedDelivery(ME);
        found(ready, merged, taken);
        when(store.mergeConfidenceOnce(2L, SixFileKind.STRUCTURED, 20L, 10L, ME)).thenReturn(7);
        when(store.mergeConfidenceOnce(3L, SixFileKind.OPTIONS, 30L, 10L, ME)).thenReturn(-1);   // other server
        when(store.takeRow(1L, ME)).thenReturn(true);

        poller.generateExportFiles();

        verify(deliveryService, times(2)).keepWaitingFilesAlive(-1L);
        verify(batchProgressService).incrementExportBatchForSix(12L, 5);
        verify(batchProgressService, never()).incrementExportBatchForSix(eq(13L), anyInt());
        InOrder order = inOrder(deliveryService);
        order.verify(deliveryService).finishFile(taken, taken.members().get(1));
        order.verify(deliveryService).finishFile(taken, taken.members().get(2));
        order.verify(deliveryService).finishFile(taken, taken.instrument().get());
    }

    @Test
    public void takenRow_reReadEmpty_usesMergedDelivery() {
        SixDeliveryService.Delivery merged = mergedDelivery(null);
        found(readyDelivery(), merged, null);
        when(store.takeRow(1L, ME)).thenReturn(true);
        poller.generateExportFiles();
        verify(deliveryService).finishFile(merged, merged.instrument().get());     // holder null: allowed
    }

    @Test
    public void mergeFails_rolledBack_noFinish() {
        found(readyDelivery());
        when(store.mergeConfidenceOnce(anyLong(), eq(SixFileKind.STRUCTURED), anyLong(), anyLong(), anyString()))
                .thenThrow(new IllegalStateException("ORA-1"));
        poller.generateExportFiles();
        verify(deliveryService).rollbackDelivery(K, null, "confidence update of STRUCTURED failed: ORA-1");
        verify(store, never()).mergeConfidenceOnce(anyLong(), eq(SixFileKind.OPTIONS), anyLong(), anyLong(), anyString());
        verify(store, never()).takeRow(anyLong(), anyString());
    }

    @Test
    public void allMerged_reReadGone_orOtherDelivery_waits() {
        found(mergedDeliveryNotFinishing(), (SixDeliveryService.Delivery) null);
        poller.generateExportFiles();
        SixDeliveryService.Delivery other = SixDeliveryFixtures.delivery(K + 1);
        found(mergedDeliveryNotFinishing(), other);
        poller.generateExportFiles();
        verify(store, never()).takeRow(anyLong(), anyString());
    }

    private SixDeliveryService.Delivery mergedDeliveryNotFinishing() {
        return mergedDelivery(null);
    }

    @Test
    public void otherServerStillMerging_waits() {
        found(readyDelivery(), readyDelivery());
        when(store.mergeConfidenceOnce(anyLong(), any(), anyLong(), anyLong(), anyString())).thenReturn(-1);
        poller.generateExportFiles();
        verify(store, never()).takeRow(anyLong(), anyString());
    }

    @Test
    public void reReadWithoutInstrumentRow_noTake() {
        SixDeliveryService.Delivery noRow = SixDeliveryFixtures.delivery(K);
        SixDeliveryFixtures.pending(noRow, SixFileKind.INSTRUMENT);
        SixDeliveryService.Delivery noInstrument = SixDeliveryFixtures.delivery(K);
        found(mergedDelivery(null), noRow, mergedDelivery(null), noInstrument);
        poller.generateExportFiles();
        poller.generateExportFiles();
        verify(store, never()).takeRow(anyLong(), anyString());
    }

    @Test
    public void takeRowLost_noFinish() {
        found(mergedDelivery(null), mergedDelivery(null));
        when(store.takeRow(1L, ME)).thenReturn(false);
        poller.generateExportFiles();
        verify(deliveryService, never()).finishFile(any(), any());
    }

    @Test
    public void takenRow_reReadWithoutInstrumentOrRow_noFinish() {
        SixDeliveryService.Delivery noInstrument = SixDeliveryFixtures.delivery(K);
        SixDeliveryService.Delivery noRow = SixDeliveryFixtures.delivery(K);
        SixDeliveryFixtures.pending(noRow, SixFileKind.INSTRUMENT);
        when(store.takeRow(1L, ME)).thenReturn(true);
        found(mergedDelivery(null), mergedDelivery(null), noInstrument, mergedDelivery(null), mergedDelivery(null), noRow);
        poller.generateExportFiles();
        poller.generateExportFiles();
        verify(deliveryService, never()).finishFile(any(), any());
    }

    // ------------------------------------------------------------------ finishing (resume / take over)

    @Test
    public void finishingByMe_resumed() {
        SixDeliveryService.Delivery d = mergedDelivery(ME);
        found(d);
        poller.generateExportFiles();
        verify(deliveryService).finishFile(d, d.instrument().get());
        verify(store, never()).mergeConfidenceOnce(anyLong(), any(), anyLong(), anyLong(), anyString());
    }

    @Test
    public void finishingByLiveServer_leftAlone() {
        found(mergedDelivery("n2"));
        when(heartbeatService.isNodeAlive("n2")).thenReturn(true);
        poller.generateExportFiles();
        verify(deliveryService, never()).finishFile(any(), any());
        verify(store, never()).takeOverRow(anyLong(), anyString(), anyString());
    }

    @Test
    public void finishingByDeadServer_takeOverLost() {
        found(mergedDelivery("n2"));
        when(store.takeOverRow(1L, "n2", ME)).thenReturn(false);
        poller.generateExportFiles();
        verify(deliveryService, never()).finishFile(any(), any());
    }

    @Test
    public void finishingByDeadServer_takenOver() {
        SixDeliveryService.Delivery d = mergedDelivery("n2");
        found(d);
        when(store.takeOverRow(1L, "n2", ME)).thenReturn(true);
        poller.generateExportFiles();
        verify(deliveryService).finishFile(d, d.instrument().get());
    }

    @Test
    public void finishFails_resumedNextTick() {
        SixDeliveryService.Delivery d = mergedDelivery(ME);
        found(d);
        doThrow(new IllegalStateException("disk")).when(deliveryService).finishFile(eq(d), any());
        poller.generateExportFiles();
        verify(deliveryService, never()).finishFile(d, d.instrument().get());
    }
}
