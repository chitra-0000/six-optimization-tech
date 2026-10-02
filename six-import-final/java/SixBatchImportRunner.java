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

    /** XmlMapper is thread-safe once configured: build it once, not per file. */
    private final XmlMapper xmlMapper = new XmlMapper();

    /** Writes one batch in its own transaction. */
    public interface BatchWriter<E> {
        void write(List<E> batch, Version version, ReglissList list) throws Exception;
    }

    public <D, E> int run(String label, File transformedXml, String recordElement, Class<D> dtoClass,
                          Function<D, E> mapper, BatchWriter<E> writer,
                          Version version, ReglissList list, long batchExecutionId) throws Exception {
        long start = System.currentTimeMillis();
        SixXmlRecordStream stream = new SixXmlRecordStream(xmlMapper);

        final int totalRecords = stream.count(transformedXml, recordElement);
        if (totalRecords == 0) {
            log.warn("[{}] No <{}> records found after transformation - check the XSLT output element name.",
                    label, recordElement);
            return 0;
        }
        log.info("[{}] {} records, batches of {}, {} writer threads", label, totalRecords, dbBatchSize, threadPoolSize);

        SixImportExecutor executor = new SixImportExecutor("six-import-" + label, threadPoolSize, requestContext);
        AtomicInteger persisted = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicReference<Exception> firstError = new AtomicReference<>();
        List<E> current = new ArrayList<>(dbBatchSize);

        try {
            stream.forEach(transformedXml, recordElement, dtoClass, dto -> {
                // FAIL FAST: once a batch has failed, stop reading the file - no new batch is written.
                if (failOnBatchError && firstError.get() != null) {
                    throw new StopAfterFailedBatch();
                }
                current.add(mapper.apply(dto));
                if (current.size() >= dbBatchSize) {
                    submit(executor, writer, new ArrayList<>(current), version, list, batchExecutionId,
                            totalRecords, persisted, failed, firstError);
                    current.clear();
                }
            });
            if (!current.isEmpty() && !(failOnBatchError && firstError.get() != null)) {
                submit(executor, writer, new ArrayList<>(current), version, list, batchExecutionId,
                        totalRecords, persisted, failed, firstError);
            }
        } catch (StopAfterFailedBatch stop) {
            log.warn("[{}] a batch failed - stopped reading the file, no further batches are written", label);
        } finally {
            executor.waitUntilFinished();
        }

        log.info("[{}] DB write finished in {}s: {} persisted, {} failed (of {})",
                label, (System.currentTimeMillis() - start) / 1000, persisted.get(), failed.get(), totalRecords);

        if (firstError.get() != null && failOnBatchError) {
            // The caller (AutomaticSixImportXmlService) deletes the rows already committed for this version.
            throw new ReglissException("SIX " + label + " import failed after " + persisted.get() + " of " + totalRecords
                    + " records were saved (they will be removed). First error: " + firstError.get().getMessage());
        }
        return persisted.get();
    }

    private <E> void submit(SixImportExecutor executor, BatchWriter<E> writer, List<E> batch,
                            Version version, ReglissList list, long batchExecutionId, int totalRecords,
                            AtomicInteger persisted, AtomicInteger failed, AtomicReference<Exception> firstError) {
        executor.blockingSubmit(() -> {
            // A batch that was already queued when another batch failed is not written.
            if (failOnBatchError && firstError.get() != null) {
                return;
            }
            try {
                writer.write(batch, version, list);
                persisted.addAndGet(batch.size());
                progressService.incrementImportAutoBatchExecution(batchExecutionId, ImportAutoPhase.IMPORT_AUTO_XML,
                        list.getImportConfiguration(), batch.size(), totalRecords);
            } catch (Exception e) {
                failed.addAndGet(batch.size());
                firstError.compareAndSet(null, e);
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
