package com.bnpp.regliss.importer.six.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

// TODO: re-add project imports for: ReglissBatchProfile, ReglissRequestContext, FilteredDataMapper,
// InstrumentFile, StructuredFile, OptionsFile, FilteredInstrumentFile, FilteredStructuredFile, FilteredOptionsFile

/**
 * Writes the filtered rows of one output list (FILTERED_SIX_* + FILTERED_SIX_TARGET).
 *
 * Replaces Filtered*ImportService.insertObjects for the export:
 *  - plain JDBC batches through SixJdbcBulkWriter (same writer as the import): the filtered entities use
 *    IDENTITY ids, so JPA sent one INSERT per row and per target (about 60 minutes in the export log);
 *  - its own worker pool (SixImportExecutor), not the shared BackpressureExecutor;
 *  - FAIL FAST: a failed batch stops the export of this version with the error. Before, the error was only
 *    logged and the XML file was generated without the rows of that batch (silent data loss);
 *  - same mapping as before (FilteredDataMapper) and same text truncation as JPA (EntityUtils, in the writer).
 */
@Component
@ReglissBatchProfile
@Slf4j
public class SixFilteredRowWriter {

    @Value("${six.db.batch}")
    private int dbBatchSize;

    @Value("${automatic.import.db.thread.pool.size}")
    private int threadPoolSize;

    @Autowired
    private SixJdbcBulkWriter bulkWriter;

    @Autowired
    private FilteredDataMapper filteredDataMapper;

    @Autowired
    private ReglissRequestContext requestContext;

    /**
     * @param rows      InstrumentFile / StructuredFile / OptionsFile rows kept by the filters
     * @param progress  receives percentForWrite spread over the batches
     * @return number of parent rows written
     */
    public int write(SixFileKind kind, List<?> rows, String listRef, long rawListId, long versionId,
                     SixExportProgress progress, double percentForWrite) {
        if (rows.isEmpty()) {
            progress.add(percentForWrite);
            return 0;
        }
        int batchSize = Math.max(1, dbBatchSize);
        int batches = (rows.size() + batchSize - 1) / batchSize;
        double percentPerBatch = percentForWrite / batches;
        AtomicInteger written = new AtomicInteger();
        AtomicInteger targets = new AtomicInteger();
        long start = System.currentTimeMillis();

        SixImportExecutor executor = new SixImportExecutor("six-export-" + kind.name().toLowerCase(Locale.ROOT),
                Math.max(1, threadPoolSize), requestContext);
        try {
            for (int from = 0; from < rows.size(); from += batchSize) {
                List<?> slice = new ArrayList<>(rows.subList(from, Math.min(from + batchSize, rows.size())));
                executor.blockingSubmit(() -> {
                    targets.addAndGet(writeBatch(kind, slice, listRef, rawListId, versionId));
                    written.addAndGet(slice.size());
                    progress.add(percentPerBatch);
                });
            }
        } catch (RuntimeException e) {
            stopQuietly(executor);
            throw e;
        }
        executor.waitUntilFinished();          // throws if a batch failed

        log.info("SIX export {} list {} version {}: {} rows + {} targets written in {} ms", kind, listRef, versionId,
                written.get(), targets.get(), System.currentTimeMillis() - start);
        return written.get();
    }

    /** One batch: map to the filtered entities and insert parents + targets in one transaction. */
    private int writeBatch(SixFileKind kind, List<?> slice, String listRef, long rawListId, long versionId) {
        switch (kind) {
            case INSTRUMENT: {
                List<FilteredInstrumentFile> out = new ArrayList<>(slice.size());
                for (Object row : slice) {
                    out.add(filteredDataMapper.filteredInstrumentFile((InstrumentFile) row, listRef));
                }
                return bulkWriter.insertBatch(out, rawListId, versionId, FilteredInstrumentFile::getSixTargets,
                        kind.getTargetFkColumn());
            }
            case STRUCTURED: {
                List<FilteredStructuredFile> out = new ArrayList<>(slice.size());
                for (Object row : slice) {
                    out.add(filteredDataMapper.filteredStructuredFile((StructuredFile) row, listRef));
                }
                return bulkWriter.insertBatch(out, rawListId, versionId, FilteredStructuredFile::getSixTargets,
                        kind.getTargetFkColumn());
            }
            case OPTIONS: {
                List<FilteredOptionsFile> out = new ArrayList<>(slice.size());
                for (Object row : slice) {
                    out.add(filteredDataMapper.filteredOptionsFile((OptionsFile) row, listRef));
                }
                return bulkWriter.insertBatch(out, rawListId, versionId, FilteredOptionsFile::getSixTargets,
                        kind.getTargetFkColumn());
            }
            default:
                throw new IllegalArgumentException("Unsupported SIX file type " + kind);
        }
    }

    private static void stopQuietly(SixImportExecutor executor) {
        try {
            executor.waitUntilFinished();
        } catch (RuntimeException e) {
            log.debug("Writer pool stopped after an error: {}", e.getMessage());
        }
    }
}
