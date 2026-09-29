package com.bnpp.regliss.importer.six.service;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;          // use jakarta.persistence.* if the project is on Spring Boot 3
import javax.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

// TODO: lines 1-34 (package, imports, first class annotations) were not in the photos.
//       @Service / @ReglissBatchProfile are assumed from the other two import services - check in the IDE.
// TODO: re-add project imports (Alt+Enter / Optimize Imports) for:
// ReglissBatchProfile, CloseResourcesAfter, ReglissRequestContext, BatchProgressService, BackpressureExecutor, BatchPersistWorker,
// ImportAutoPhase, Version, ReglissList, StructuredFile, StructuredFileDto, StructuredFileMapper, StructureFileRepository

@Service
@ReglissBatchProfile
@Transactional
@Slf4j
public class StructuredFileImportService {

    @Value("${six.db.batch}")
    private int dbBatchSize;

    @Value("${automatic.import.page.size}")
    private int pageSize;

    @Value("${automatic.import.db.thread.pool.size:4}")
    private int threadPoolSize;

    @Autowired
    private StructuredFileMapper structuredFileMapper;

    @Autowired
    private StructureFileRepository structureFileRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private BatchProgressService progressService;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ReglissRequestContext requestContext;


    @CloseResourcesAfter
    public void streamAndPersist(String transformedXml, Version version, ReglissList list, long batchExecutionId) throws Exception {
        XmlMapper xmlMapper = new XmlMapper();
        log.info("Starting parallel streaming import for structure...");

        StructuredFilesWrapper wrapper = xmlMapper.readValue(transformedXml, StructuredFilesWrapper.class);

        if (wrapper == null || wrapper.getStructuredFiles() == null || wrapper.getStructuredFiles().isEmpty()) {
            log.warn("No data found in structure  after transformation.");
            return;
        }

        List<StructuredFileDto> allDtos = wrapper.getStructuredFiles();
        int totalRecords = allDtos.size(); // Total to be processed

        BackpressureExecutor executor = new BackpressureExecutor("six-import", threadPoolSize, requestContext);
        List<StructuredFile> currentBatch = new ArrayList<>(dbBatchSize);

        AtomicLong totalPersistedSoFar = new AtomicLong(0);

        log.info("Processing {} structure records in batches of {}", totalRecords, dbBatchSize);

        for (StructuredFileDto dto : allDtos) {
            if (dto == null) continue;

            currentBatch.add(structuredFileMapper.mapperStructuredFile(dto));

            if (currentBatch.size() >= dbBatchSize) {
                List<StructuredFile> batchToProcess = new ArrayList<>(currentBatch);

                executor.blockingSubmit(() -> {
                    processBatchWithProgress(batchToProcess, version, list, batchExecutionId, totalRecords);
                });

                currentBatch.clear();
            }
        }

        if (!currentBatch.isEmpty()) {
            List<StructuredFile> finalBatch = new ArrayList<>(currentBatch);
            executor.blockingSubmit(() -> {
                processBatchWithProgress(finalBatch, version, list, batchExecutionId, totalRecords);
            });
        }

        executor.shutdownAndWaitToDie();
        log.info("Structure import completed. Total records processed: {}", totalPersistedSoFar.get());
    }

    private void processBatchWithProgress(
            List<StructuredFile> batch,
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
