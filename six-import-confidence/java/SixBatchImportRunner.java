package com.bnpp.regliss.importer.six.service;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

// TODO: re-add project imports for:
// ReglissBatchProfile, ReglissRequestContext, BatchProgressService,
// ImportAutoPhase, Version, ReglissList, ReglissException

/**
 * One shared "read records -> map to entity -> write in parallel batches" loop for the
 * three SIX imports (the three services used to carry three copies of it).
 *
 *  - Records are read ONE AT A TIME from the transformed file (SixXmlRecordStream);
 *    only the batches being written are in memory, never the whole file.
 *  - Batches of six.db.batch rows are written by automatic.import.db.thread.pool.size
 *    threads (SixImportExecutor; the shared BackpressureExecutor is not used or changed), each batch in its own transaction.
 *  - FAIL FAST: on the first failed batch the file is no longer read and no further batch is
 *    written; batches already running finish, and the import fails. The caller then removes
 *    every row already committed for this version. (Before, a failed batch was only logged and
 *    up to 1000 sanctions records disappeared while the run still looked successful.)
 *  - No @Transactional here: the old class-level @Transactional kept a connection open
 *    for the whole import for nothing.
 */
@Service
@ReglissBatchProfile
@Slf4j
public class SixBatchImportRunner {

    @Value("${six.db.batch}")
    private int dbBatchSize;

    @Value("${automatic.import.db.thread.pool.size}")
    private int threadPoolSize;

    @Value("${six.import.fail-on-batch-error:true}")
    private boolean failOnBatchError;


    @Autowired
    private ReglissRequestContext requestContext;

    @Autowired
    private BatchProgressService progressService;

    @Autowired
    private SixDeliveryService deliveryService;

    /** XmlMapper is thread-safe once configured: build it once, not per file. */
    private final XmlMapper xmlMapper = new XmlMapper();

    /** Writes one batch in its own transaction. */
    public interface BatchWriter<E> {
        void write(List<E> batch, Version version, ReglissList list);
    }

    /**
     * What is imported for one SIX file type: log label, XML record element, DTO class,
     * DTO -> entity mapper and batch writer. Built by each *FileImportService.
     */
    public static final class RecordType<D, E> {
        private final String label;
        private final String recordElement;
        private final Class<D> dtoClass;
        private final Function<D, E> mapper;
        private final BatchWriter<E> writer;

        public RecordType(String label, String recordElement, Class<D> dtoClass, Function<D, E> mapper, BatchWriter<E> writer) {
            this.label = label;
            this.recordElement = recordElement;
            this.dtoClass = dtoClass;
            this.mapper = mapper;
            this.writer = writer;
        }
    }

    /** Everything one run shares between the reading thread and the writer threads. */
    private static final class RunState<E> {
        private final SixImportExecutor executor;
        private final BatchWriter<E> writer;
        private final Version version;
        private final ReglissList list;
        private final long batchExecutionId;
        private final int totalRecords;
        private final AtomicInteger persisted = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();
        private final AtomicReference<Exception> firstError = new AtomicReference<>();

        private RunState(SixImportExecutor executor, BatchWriter<E> writer, Version version, ReglissList list,
                         long batchExecutionId, int totalRecords) {
            this.executor = executor;
            this.writer = writer;
            this.version = version;
            this.list = list;
            this.batchExecutionId = batchExecutionId;
            this.totalRecords = totalRecords;
        }
    }

    public <D, E> int run(RecordType<D, E> type, File transformedXml,
                          Version version, ReglissList list, long batchExecutionId) throws Exception {
        long start = System.currentTimeMillis();
        SixXmlRecordStream stream = new SixXmlRecordStream(xmlMapper);

        final int totalRecords = stream.count(transformedXml, type.recordElement);
        if (totalRecords == 0) {
            log.warn("[{}] No <{}> records found after transformation - check the XSLT output element name.",
                    type.label, type.recordElement);
            return 0;
        }
        log.info("[{}] {} records, batches of {}, {} writer threads", type.label, totalRecords, dbBatchSize, threadPoolSize);

        RunState<E> state = new RunState<>(new SixImportExecutor("six-import-" + type.label, threadPoolSize, requestContext),
                type.writer, version, list, batchExecutionId, totalRecords);
        List<E> current = new ArrayList<>(dbBatchSize);

        try {
            stream.forEach(transformedXml, type.recordElement, type.dtoClass, dto -> {
                // FAIL FAST: once a batch has failed, stop reading the file - no new batch is written.
                if (stopAfterError(state)) {
                    throw new StopAfterFailedBatch();
                }
                current.add(type.mapper.apply(dto));
                if (current.size() >= dbBatchSize) {
                    submit(state, new ArrayList<>(current));
                    current.clear();
                }
            });
            if (!current.isEmpty() && !stopAfterError(state)) {
                submit(state, new ArrayList<>(current));
            }
        } catch (StopAfterFailedBatch stop) {
            log.warn("[{}] a batch failed - stopped reading the file, no further batches are written", type.label);
        } finally {
            state.executor.waitUntilFinished();
        }

        log.info("[{}] DB write finished in {}s: {} persisted, {} failed (of {})",
                type.label, (System.currentTimeMillis() - start) / 1000, state.persisted.get(), state.failed.get(), totalRecords);

        if (stopAfterError(state)) {
            // The caller (AutomaticSixImportXmlService) deletes the rows already committed for this version.
            throw new ReglissException("SIX " + type.label + " import failed after " + state.persisted.get() + " of " + totalRecords
                    + " records were saved (they will be removed). First error: " + state.firstError.get().getMessage());
        }
        return state.persisted.get();
    }

    private boolean stopAfterError(RunState<?> state) {
        return failOnBatchError && state.firstError.get() != null;
    }

    private <E> void submit(RunState<E> state, List<E> batch) {
        state.executor.blockingSubmit(() -> {
            // A batch that was already queued when another batch failed is not written.
            if (stopAfterError(state)) {
                return;
            }
            try {
                state.writer.write(batch, state.version, state.list);
                state.persisted.addAndGet(batch.size());
                progressService.incrementImportAutoBatchExecution(state.batchExecutionId, ImportAutoPhase.IMPORT_AUTO_XML,
                        state.list.getImportConfiguration(), batch.size(), state.totalRecords);
                // This file is progressing: keep the files of its delivery that wait at 90% from being
                // reported as stuck (at most once a minute; never fails the import).
                deliveryService.keepWaitingFilesAlive(state.batchExecutionId);
            } catch (Exception e) {
                state.failed.addAndGet(batch.size());
                state.firstError.compareAndSet(null, e);
                log.error("Failed to persist batch of size {}", batch.size(), e);
            }
        });
    }

    /** Internal signal used to stop reading the file after the first failed batch. */
    private static final class StopAfterFailedBatch extends RuntimeException {
        StopAfterFailedBatch() {
            super(null, null, false, false);   // no stack trace: it is a control signal, not an error
        }
    }
}
