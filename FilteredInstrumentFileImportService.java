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
// ReglissBatchProfile, FilteredDataMapper, InstrumentFile, FilteredInstrumentFile,
// BackpressureExecutor, BatchPersistWorker, ReglissRequestContext

@Service
@ReglissBatchProfile
@Transactional
@Slf4j
public class FilteredInstrumentFileImportService {

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

    public void insertObjects(List<InstrumentFile> includedEntries, String listReference) {

        BackpressureExecutor executor = new BackpressureExecutor("six-import", threadPoolSize, requestContext);
        List<FilteredInstrumentFile> currentBatch = new ArrayList<>(dbBatchSize);

        for (InstrumentFile instrumentFile : includedEntries) {
            if (instrumentFile == null) continue;

            currentBatch.add(filteredDataMapper.filteredInstrumentFile(instrumentFile, listReference));

            if (currentBatch.size() >= dbBatchSize) {
                List<FilteredInstrumentFile> batchToProcess = new ArrayList<>(currentBatch);

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
            List<FilteredInstrumentFile> finalBatch = new ArrayList<>(currentBatch);
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
        log.info("Filtered structure import completed");

    }


}
