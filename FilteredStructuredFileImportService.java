package com.bnpp.regliss.importer.six.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;          // use jakarta.persistence.* if the project is on Spring Boot 3
import javax.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.List;

// TODO: lines 1-53 were not in the screenshot. The header below (annotations, fields, method signature)
//       is taken from FilteredInstrumentFileImportService, which has the same layout - check it in the IDE.
// Re-add project imports with Alt+Enter / Optimize Imports for:
// ReglissBatchProfile, FilteredDataMapper, StructuredFile, FilteredStructuredFile,
// BackpressureExecutor, BatchPersistWorker, ReglissRequestContext

@Service
@ReglissBatchProfile
@Transactional
@Slf4j
public class FilteredStructuredFileImportService {

    @Value("${six.db.batch}")
    private int dbBatchSize;

    @Value("${automatic.import.db.thread.pool.size}")
    private int threadPoolSize;

    @Autowired
    private FilteredDataMapper filteredDataMapper;

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ReglissRequestContext requestContext;

    public void insertObjects(List<StructuredFile> includedEntries, String listReference) {

        BackpressureExecutor executor = new BackpressureExecutor("six-import", threadPoolSize, requestContext);
        List<FilteredStructuredFile> currentBatch = new ArrayList<>(dbBatchSize);

        for (StructuredFile structuredFile : includedEntries) {
            if (structuredFile == null) continue;

            currentBatch.add(filteredDataMapper.filteredStructuredFile(structuredFile, listReference));

            if (currentBatch.size() >= dbBatchSize) {
                List<FilteredStructuredFile> batchToProcess = new ArrayList<>(currentBatch);

                executor.blockingSubmit(() -> {
                    try {
                        BatchPersistWorker worker = applicationContext.getBean(BatchPersistWorker.class);
                        worker.persistBatchFiltered(batchToProcess);
                    } catch (Exception e) {
                        log.error("Failed to persist batch of size {}", batchToProcess.size(), e);
                    }
                });

                currentBatch.clear();
            }
        }

        if (!currentBatch.isEmpty()) {
            List<FilteredStructuredFile> finalBatch = new ArrayList<>(currentBatch);
            executor.blockingSubmit(() -> {
                try {
                    BatchPersistWorker worker = applicationContext.getBean(BatchPersistWorker.class);
                    worker.persistBatchFiltered(finalBatch);
                } catch (Exception e) {
                    log.error("Failed to persist batch of size {}", finalBatch.size(), e);
                }
            });
        }

        executor.shutdownAndWaitToDie();
        log.info("Filtered instrument import completed");

    }
}
