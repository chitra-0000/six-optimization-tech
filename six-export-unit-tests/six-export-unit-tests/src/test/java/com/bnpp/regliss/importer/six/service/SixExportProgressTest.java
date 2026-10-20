package com.bnpp.regliss.importer.six.service;

import org.junit.After;
import org.junit.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// TODO: re-add project imports for: BatchProgressService

public class SixExportProgressTest {

    private final BatchProgressService progressService = mock(BatchProgressService.class);
    private SixExportProgress progress;

    @After
    public void stop() {
        if (progress != null) {
            progress.stopKeepAlive();
        }
    }

    @Test
    public void listShare() {
        assertEquals(0.0, SixExportProgress.listShare(0), 0.0);
        assertEquals(0.0, SixExportProgress.listShare(-1), 0.0);
        assertEquals(45.0, SixExportProgress.listShare(2), 0.0);
        assertEquals(95.0, SixExportProgress.AUTOMATIC_MAX, 0.0);
        assertEquals(1.0, SixExportProgress.FILTER_PART + SixExportProgress.DELETE_PART + SixExportProgress.WRITE_PART
                + SixExportProgress.XML_PART, 1e-9);
    }

    @Test
    public void add_wholePercentsOnly_neverAboveTheMaximum() {
        progress = new SixExportProgress(progressService, 7L);
        progress.add(0.6);                  // nothing sent yet
        progress.add(0.6);                  // 1.2 -> 1 sent, 0.2 kept
        progress.add(-3);                   // ignored
        progress.add(0);                    // ignored
        progress.add(100);                  // capped: 94 more
        progress.add(10);                   // already at the maximum
        InOrder order = inOrder(progressService);
        order.verify(progressService).incrementExportBatchForSix(7L, 1);
        order.verify(progressService).incrementExportBatchForSix(7L, 94);
        order.verifyNoMoreInteractions();
    }

    @Test
    public void add_withoutJob_nothingSent() {
        progress = new SixExportProgress(progressService, null);
        progress.add(50);
        verify(progressService, never()).incrementExportBatchForSix(anyLong(), anyInt());
    }

    @Test
    public void add_progressErrorIgnored() {
        doThrow(new IllegalStateException("db")).when(progressService).incrementExportBatchForSix(7L, 3);
        progress = new SixExportProgress(progressService, 7L);
        progress.add(3);
        progress.add(1);
        verify(progressService).incrementExportBatchForSix(7L, 1);
    }

    @Test
    public void keepAlive_startStop() throws Exception {
        progress = new SixExportProgress(progressService, 7L);
        progress.startKeepAlive();
        ScheduledExecutorService first = (ScheduledExecutorService) ReflectionTestUtils.getField(progress, "keepAlive");
        assertNotNull(first);
        progress.startKeepAlive();                                       // already running: same timer
        assertSame(first, ReflectionTestUtils.getField(progress, "keepAlive"));

        // run the scheduled refresh now instead of waiting one minute
        ScheduledThreadPoolExecutor pool = (ScheduledThreadPoolExecutor) ReflectionTestUtils.getField(first, "e");
        pool.getQueue().peek().run();
        verify(progressService).incrementExportBatchForSix(7L, 0);

        progress.stopKeepAlive();
        assertNull(ReflectionTestUtils.getField(progress, "keepAlive"));
        assertTrue(first.isShutdown());
        progress.stopKeepAlive();                                        // already stopped: nothing to do
    }

    @Test
    public void keepAlive_withoutJob_notStarted() {
        progress = new SixExportProgress(progressService, null);
        progress.startKeepAlive();
        assertNull(ReflectionTestUtils.getField(progress, "keepAlive"));
    }
}
