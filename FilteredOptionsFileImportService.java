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

// TODO: the project imports were collapsed ("import ...") in the screenshots.
// Re-add them in the IDE (Alt+Enter / Optimize Imports) for:
// ReglissBatchProfile, FilteredDataMapper, OptionsFile, FilteredOptionsFile,
// BackpressureExecutor, BatchPersistWorker, ReglissRequestContext

@Service
@ReglissBatchProfile
@Transactional
@Slf4j
public class FilteredOptionsFileImportService {

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

    public void insertObjects(List<OptionsFile> includedEntries, String listReference) {

        BackpressureExecutor executor = new BackpressureExecutor("six-import", threadPoolSize, requestContext);
        List<FilteredOptionsFile> currentBatch = new ArrayList<>(dbBatchSize);

        for (OptionsFile optionsFile : includedEntries) {
            if (optionsFile == null) continue;

            currentBatch.add(filteredDataMapper.filteredOptionsFile(optionsFile, listReference));

            if (currentBatch.size() >= dbBatchSize) {
                List<FilteredOptionsFile> batchToProcess = new ArrayList<>(currentBatch);

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
            List<FilteredOptionsFile> finalBatch = new ArrayList<>(currentBatch);
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
        log.info("Filtered option import completed");

    }



}
