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
// ReglissBatchProfile, ReglissRequestContext, BatchProgressService, BackpressureExecutor,
// ImportAutoPhase, Version, ReglissList, ReglissException

/**
 * One shared "read records -> map to entity -> write in parallel batches" loop for the
 * three SIX imports (the three services used to carry three copies of it).
 *
 *  - Records are read ONE AT A TIME from the transformed file (SixXmlRecordStream);
 *    only the batches being written are in memory, never the whole file.
 *  - Batches of six.db.batch rows are written by automatic.import.db.thread.pool.size
 *    threads (BackpressureExecutor), each batch in its own transaction.
 *  - A failed batch is counted and the import FAILS at the end. Before, a failed batch
 *    was only logged and up to 1000 sanctions records disappeared while the run still
 *    looked successful.
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

        BackpressureExecutor executor = new BackpressureExecutor("six-import-" + label, threadPoolSize, requestContext);
        AtomicInteger persisted = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicReference<Exception> firstError = new AtomicReference<>();
        List<E> current = new ArrayList<>(dbBatchSize);

        try {
            stream.forEach(transformedXml, recordElement, dtoClass, dto -> {
                current.add(mapper.apply(dto));
                if (current.size() >= dbBatchSize) {
                    submit(executor, writer, new ArrayList<>(current), version, list, batchExecutionId,
                            totalRecords, persisted, failed, firstError);
                    current.clear();
                }
            });
            if (!current.isEmpty()) {
                submit(executor, writer, new ArrayList<>(current), version, list, batchExecutionId,
                        totalRecords, persisted, failed, firstError);
            }
        } finally {
            executor.shutdownAndWaitToDie();
        }

        log.info("[{}] DB write finished in {}s: {} persisted, {} failed (of {})",
                label, (System.currentTimeMillis() - start) / 1000, persisted.get(), failed.get(), totalRecords);

        if (failed.get() > 0 && failOnBatchError) {
            throw new ReglissException("SIX " + label + " import incomplete: " + failed.get() + " of " + totalRecords
                    + " records were not saved. First error: "
                    + (firstError.get() == null ? "n/a" : firstError.get().getMessage()));
        }
        return persisted.get();
    }

    private <E> void submit(BackpressureExecutor executor, BatchWriter<E> writer, List<E> batch,
                            Version version, ReglissList list, long batchExecutionId, int totalRecords,
                            AtomicInteger persisted, AtomicInteger failed, AtomicReference<Exception> firstError) {
        executor.blockingSubmit(() -> {
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
}
