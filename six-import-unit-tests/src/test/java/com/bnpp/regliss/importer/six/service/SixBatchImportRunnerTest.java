package com.bnpp.regliss.importer.six.service;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// TODO: re-add project imports for:
// ReglissRequestContext, BatchProgressService, ImportAutoPhase, ImportConfiguration, Version, ReglissList, ReglissException

@RunWith(MockitoJUnitRunner.Silent.class)
public class SixBatchImportRunnerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @InjectMocks
    private SixBatchImportRunner runner;

    @Mock
    private ReglissRequestContext requestContext;
    @Mock
    private BatchProgressService progressService;
    @Mock
    private SixDeliveryService deliveryService;
    @Mock
    private Version version;
    @Mock
    private ReglissList list;
    @Mock
    private ImportConfiguration importConfiguration;

    /** Record bound from the XML. */
    public static class Rec {
        public String id;
    }

    @Before
    public void setUp() {
        ReflectionTestUtils.setField(runner, "dbBatchSize", 2);
        ReflectionTestUtils.setField(runner, "threadPoolSize", 2);
        ReflectionTestUtils.setField(runner, "failOnBatchError", true);
        when(list.getImportConfiguration()).thenReturn(importConfiguration);
    }

    private File file(int records) throws Exception {
        StringBuilder sb = new StringBuilder("<ROOT>");
        for (int i = 1; i <= records; i++) {
            sb.append("<REC><id>").append(i).append("</id></REC>");
        }
        File f = tmp.newFile();
        Files.write(f.toPath(), sb.append("</ROOT>").toString().getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private static SixBatchImportRunner.RecordType<Rec, String> type(SixBatchImportRunner.BatchWriter<String> writer) {
        return new SixBatchImportRunner.RecordType<>("test", "REC", Rec.class, r -> r.id, writer);
    }

    @Test
    public void noRecordReturnsZeroAndWritesNothing() throws Exception {
        List<List<String>> written = new CopyOnWriteArrayList<>();
        int persisted = runner.run(type((batch, v, l) -> written.add(batch)), file(0), version, list, 7L);
        assertEquals(0, persisted);
        assertTrue(written.isEmpty());
        verifyNoInteractions(progressService, deliveryService);
    }

    @Test
    public void writesEveryRecordInBatchesIncludingTheLastPartialOne() throws Exception {
        List<List<String>> written = new CopyOnWriteArrayList<>();
        int persisted = runner.run(type((batch, v, l) -> {
            assertTrue(v == version && l == list);
            written.add(batch);
        }), file(5), version, list, 7L);

        assertEquals(5, persisted);
        assertEquals(3, written.size());
        List<String> all = new ArrayList<>();
        written.forEach(all::addAll);
        Collections.sort(all);
        assertEquals(Arrays.asList("1", "2", "3", "4", "5"), all);
        verify(progressService, times(3)).incrementImportAutoBatchExecution(eq(7L), eq(ImportAutoPhase.IMPORT_AUTO_XML),
                eq(importConfiguration), anyInt(), eq(5));
        verify(deliveryService, times(3)).keepWaitingFilesAlive(7L);
    }

    @Test
    public void failedBatchFailsTheImport() throws Exception {
        SixBatchImportRunner.RecordType<Rec, String> failing = type((batch, v, l) -> {
            throw new IllegalStateException("ORA-00001");
        });
        File input = file(1);
        try {
            runner.run(failing, input, version, list, 7L);
            fail("expected ReglissException");
        } catch (ReglissException e) {
            assertTrue(e.getMessage().contains("SIX test import failed after 0 of 1 records"));
            assertTrue(e.getMessage().contains("ORA-00001"));
        }
        verify(progressService, never()).incrementImportAutoBatchExecution(anyLong(), any(), any(), anyInt(), anyInt());
    }

    @Test
    public void failedBatchIsOnlyLoggedWhenFailOnErrorIsOff() throws Exception {
        ReflectionTestUtils.setField(runner, "failOnBatchError", false);
        int persisted = runner.run(type((batch, v, l) -> {
            if (batch.contains("1")) {
                throw new IllegalStateException("ORA-00001");
            }
        }), file(4), version, list, 7L);
        assertEquals(2, persisted);
    }

    /**
     * One writer thread, batches of one record. Batch 1 waits until record 3 is read: by then batch 2 is queued.
     * Batch 1 fails; batch 2 is skipped when it starts; the reader (blocked until batch 2 starts, so the failure is
     * already recorded) stops before record 4. Synchronised with latches only - no sleep.
     */
    @Test
    public void stopsReadingAndSkipsQueuedBatchesAfterAFailure() throws Exception {
        ReflectionTestUtils.setField(runner, "dbBatchSize", 1);
        ReflectionTestUtils.setField(runner, "threadPoolSize", 1);
        CountDownLatch thirdRecordRead = new CountDownLatch(1);
        List<String> attempted = new CopyOnWriteArrayList<>();
        SixBatchImportRunner.RecordType<Rec, String> type = new SixBatchImportRunner.RecordType<>("test", "REC", Rec.class, r -> {
            if ("3".equals(r.id)) {
                thirdRecordRead.countDown();
            }
            return r.id;
        }, (batch, v, l) -> {
            attempted.addAll(batch);
            awaitQuietly(thirdRecordRead);
            throw new IllegalStateException("batch failed");
        });
        File input = file(4);
        try {
            runner.run(type, input, version, list, 7L);
            fail("expected ReglissException");
        } catch (ReglissException e) {
            assertTrue(e.getMessage().contains("batch failed"));
        }
        assertEquals(Collections.singletonList("1"), attempted);
    }

    /**
     * Batch [1, 2] fails while record 3 (the last, partial batch) is being read: that last batch is not submitted.
     * The reader waits until the writer thread has finished the failed batch (it is then idle, waiting for work):
     * the failure is recorded by then.
     */
    @Test
    public void lastPartialBatchIsNotSubmittedAfterAFailure() throws Exception {
        ReflectionTestUtils.setField(runner, "dbBatchSize", 2);
        ReflectionTestUtils.setField(runner, "threadPoolSize", 1);
        AtomicReference<Thread> writerThread = new AtomicReference<>();
        CountDownLatch thirdRecordRead = new CountDownLatch(1);
        CountDownLatch failing = new CountDownLatch(1);
        List<List<String>> attempted = new CopyOnWriteArrayList<>();
        SixBatchImportRunner.RecordType<Rec, String> type = new SixBatchImportRunner.RecordType<>("test", "REC", Rec.class, r -> {
            if ("3".equals(r.id)) {
                thirdRecordRead.countDown();      // record 3 passed the "stop reading?" check
                awaitQuietly(failing);
                awaitIdle(writerThread.get());
            }
            return r.id;
        }, (batch, v, l) -> {
            attempted.add(batch);
            writerThread.set(Thread.currentThread());
            awaitQuietly(thirdRecordRead);        // fail only once record 3 is being read
            failing.countDown();
            throw new IllegalStateException("batch failed");
        });
        File input = file(3);
        try {
            runner.run(type, input, version, list, 7L);
            fail("expected ReglissException");
        } catch (ReglissException e) {
            assertTrue(e.getMessage().contains("after 0 of 3 records"));
        }
        assertEquals(1, attempted.size());
        assertEquals(Arrays.asList("1", "2"), attempted.get(0));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Waits until the pool thread has finished its task and waits for the next one (WAITING on the task queue). */
    private static void awaitIdle(Thread worker) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (worker.getState() != Thread.State.WAITING && System.nanoTime() < end) {
            Thread.yield();
        }
        assertEquals(Thread.State.WAITING, worker.getState());
    }
}
