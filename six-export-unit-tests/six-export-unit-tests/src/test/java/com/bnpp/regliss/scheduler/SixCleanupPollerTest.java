package com.bnpp.regliss.scheduler;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// TODO: re-add project imports for: SixCleanupService, HeartbeatService, NodeDetailsSupplier

@RunWith(MockitoJUnitRunner.Silent.class)
public class SixCleanupPollerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);

    @InjectMocks
    private SixCleanupPoller poller;

    @Mock
    private SixCleanupService sixCleanupService;
    @Mock
    private HeartbeatService heartbeatService;
    @Mock
    private NodeDetailsSupplier nodeDetailsSupplier;

    @Before
    public void setUp() {
        when(nodeDetailsSupplier.getNodeId()).thenReturn("node-a");
    }

    @Test
    public void cleanupNode_smallestAliveId() {
        assertEquals(Optional.of("node-a"), SixCleanupPoller.cleanupNode(Arrays.asList("node-b", null, "", "node-a")));
        assertFalse(SixCleanupPoller.cleanupNode(null).isPresent());
        assertFalse(SixCleanupPoller.cleanupNode(Collections.emptyList()).isPresent());
    }

    @Test
    public void runsOnTheSmallestNode_onceADay() {
        when(heartbeatService.getAllActiveBatchNodeIds()).thenReturn(Arrays.asList("node-b", "node-a"));
        poller.cleanSix(DAY);
        poller.cleanSix(DAY);                               // later tick of the same day: nothing
        verify(sixCleanupService, times(1)).runCleanup();
        poller.cleanSix(DAY.plusDays(1));
        verify(sixCleanupService, times(2)).runCleanup();
    }

    @Test
    public void otherNodeRunsIt() {
        when(heartbeatService.getAllActiveBatchNodeIds()).thenReturn(Arrays.asList("node-0", "node-a"));
        poller.cleanSix(DAY);
        verify(sixCleanupService, never()).runCleanup();
    }

    @Test
    public void noAliveServer_orHeartbeatError_triedAgainLater() {
        when(heartbeatService.getAllActiveBatchNodeIds()).thenReturn(Collections.emptyList());
        poller.cleanSix(DAY);
        when(heartbeatService.getAllActiveBatchNodeIds()).thenThrow(new IllegalStateException("db"));
        poller.cleanSix(DAY);
        verify(sixCleanupService, never()).runCleanup();
    }

    @Test
    public void cleanupError_logged_notRetriedSameDay() {
        when(heartbeatService.getAllActiveBatchNodeIds()).thenReturn(Collections.singletonList("node-a"));
        doThrow(new IllegalStateException("disk")).when(sixCleanupService).runCleanup();
        poller.cleanSix(DAY);
        poller.cleanSix(DAY);
        verify(sixCleanupService, times(1)).runCleanup();
    }

    @Test
    public void scheduledEntryPoint_usesToday() {
        when(heartbeatService.getAllActiveBatchNodeIds()).thenReturn(Collections.singletonList("node-a"));
        poller.cleanSix();
        verify(sixCleanupService).runCleanup();
    }
}
