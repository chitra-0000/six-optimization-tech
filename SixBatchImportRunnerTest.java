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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
        assertEquals(java.util.Arrays.asList("1", "2", "3", "4", "5"), all);
        verify(progressService, times(3)).incrementImportAutoBatchExecution(eq(7L), eq(ImportAutoPhase.IMPORT_AUTO_XML),
                eq(importConfiguration), anyInt(), eq(5));
        verify(deliveryService, times(3)).keepWaitingFilesAlive(7L);
    }

    @Test
    public void failedBatchFailsTheImport() throws Exception {
        try {
            runner.run(type((batch, v, l) -> {
                throw new IllegalStateException("ORA-00001");
            }), file(1), version, list, 7L);
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
     * One writer thread, batches of one record: batch 1 blocks then fails, batch 2 is already queued
     * (skipped when it starts), batch 3 is skipped too, and the reader stops before record 4.
     */
    @Test
    public void stopsReadingAndSkipsQueuedBatchesAfterAFailure() throws Exception {
        ReflectionTestUtils.setField(runner, "dbBatchSize", 1);
        ReflectionTestUtils.setField(runner, "threadPoolSize", 1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> attempted = new CopyOnWriteArrayList<>();
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(500);     // batch 2 is queued and the reader waits for a slot by then
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            release.countDown();
        });
        releaser.start();
        try {
            runner.run(type((batch, v, l) -> {
                attempted.addAll(batch);
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("batch failed");
            }), file(4), version, list, 7L);
            fail("expected ReglissException");
        } catch (ReglissException e) {
            assertTrue(e.getMessage().contains("batch failed"));
        } finally {
            releaser.join();
        }
        assertEquals(Collections.singletonList("1"), attempted);
    }
    /** Batch 1 fails while the last (partial) batch is still being read: that last batch is not submitted. */
    @Test
    public void lastPartialBatchIsNotSubmittedAfterAFailure() throws Exception {
        ReflectionTestUtils.setField(runner, "dbBatchSize", 2);
        ReflectionTestUtils.setField(runner, "threadPoolSize", 1);
        java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean();
        List<List<String>> attempted = new CopyOnWriteArrayList<>();
        SixBatchImportRunner.RecordType<Rec, String> type = new SixBatchImportRunner.RecordType<>("test", "REC", Rec.class, r -> {
            if ("3".equals(r.id)) {
                long end = System.currentTimeMillis() + 10_000;
                while (!failed.get() && System.currentTimeMillis() < end) {
                    Thread.yield();
                }
                try {
                    Thread.sleep(300);      // the failure is recorded right after the writer throws
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return r.id;
        }, (batch, v, l) -> {
            attempted.add(batch);
            failed.set(true);
            throw new IllegalStateException("batch failed");
        });
        try {
            runner.run(type, file(3), version, list, 7L);
            fail("expected ReglissException");
        } catch (ReglissException e) {
            assertTrue(e.getMessage().contains("after 0 of 3 records"));
        }
        assertEquals(1, attempted.size());
        assertEquals(java.util.Arrays.asList("1", "2"), attempted.get(0));
    }
}
