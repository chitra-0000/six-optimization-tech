package com.bnpp.regliss.importer.six.service;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import javax.persistence.EntityManager;          // use jakarta.persistence.* if the project is on Spring Boot 3
import javax.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

// TODO: re-add project imports (Alt+Enter / Optimize Imports) for:
// ReglissBatchProfile, ReglissRequestContext, BatchProgressService, BackpressureExecutor, BatchPersistWorker,
// ImportAutoPhase, Version, ReglissList, OptionsFile, OptionsFileDto, OptionsFileMapper, OptionsFileRepository

@Service
@ReglissBatchProfile
@Slf4j
public class OptionsFileImportService {

    @Value("${six.db.batch}")
    private int dbBatchSize;

    @Value("${automatic.import.db.thread.pool.size}")
    private int threadPoolSize;

    @Autowired
    private ReglissRequestContext requestContext;

    @Autowired
    private BatchProgressService progressService;

    @Autowired
    private OptionsFileMapper optionsFileMapper;

    @Autowired
    private OptionsFileRepository optionsFileRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private ApplicationContext applicationContext;


    public void streamAndPersist(String transformedXml, Version version, ReglissList list, long batchExecutionId) throws Exception {
        XmlMapper xmlMapper = new XmlMapper();
        log.info("Starting parallel streaming import for option...");

        OptionsFilesWrapper wrapper = xmlMapper.readValue(transformedXml, OptionsFilesWrapper.class);

        if (wrapper == null || wrapper.getOptionsFile() == null || wrapper.getOptionsFile().isEmpty()) {
            log.warn("No data found in Option XML after transformation.");
            return;
        }

        List<OptionsFileDto> allDtos = wrapper.getOptionsFile();
        int totalRecords = allDtos.size(); // Total to be processed

        BackpressureExecutor executor = new BackpressureExecutor("six-import", threadPoolSize, requestContext);
        List<OptionsFile> currentBatch = new ArrayList<>(dbBatchSize);

        AtomicLong totalPersistedSoFar = new AtomicLong(0);

        log.info("Processing {} options records in batches of {}", totalRecords, dbBatchSize);

        for (OptionsFileDto dto : allDtos) {
            if (dto == null) continue;

            currentBatch.add(optionsFileMapper.mapperStructuredFile(dto));

            if (currentBatch.size() >= dbBatchSize) {
                List<OptionsFile> batchToProcess = new ArrayList<>(currentBatch);

                executor.blockingSubmit(() -> {
                    processBatchWithProgress(batchToProcess, version, list, batchExecutionId, totalRecords);
                });

                currentBatch.clear();
            }
        }

        if (!currentBatch.isEmpty()) {
            List<OptionsFile> finalBatch = new ArrayList<>(currentBatch);
            executor.blockingSubmit(() -> {
                processBatchWithProgress(finalBatch, version, list, batchExecutionId, totalRecords);
            });
        }

        executor.shutdownAndWaitToDie();
        log.info("Option import completed. Total records processed: {}", totalPersistedSoFar.get());
    }

    private void processBatchWithProgress(
            List<OptionsFile> batch,
            Version version,
            ReglissList list,
            long batchExecutionId,
            int totalRecords) {

        try {
            BatchPersistWorker worker = applicationContext.getBean(BatchPersistWorker.class);
            worker.persistBatchRaw(batch, version, list);
            long countInThisBatch = batch.size();

            progressService.incrementImportAutoBatchExecution(
                    batchExecutionId,
                    ImportAutoPhase.IMPORT_AUTO_XML,
                    list.getImportConfiguration(),
                    countInThisBatch,
                    totalRecords);

        } catch (Exception e) {
            log.error("Failed to persist batch of size {}", batch.size(), e);
        }
    }
}
