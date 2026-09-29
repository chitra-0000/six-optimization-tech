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

// TODO: re-add project imports (Alt+Enter / Optimize Imports) for:
// ReglissBatchProfile, ReglissRequestContext, BatchProgressService, BackpressureExecutor, BatchPersistWorker,
// ImportAutoPhase, Version, ReglissList, InstrumentFile, InstrumentFilesDto, InstrumentFileMapper

@Service
@ReglissBatchProfile
@Transactional
@Slf4j
public class InstrumentFileImportService {

    @Value("${six.db.batch}")
    private int dbBatchSize;

    @Value("${automatic.import.db.thread.pool.size}")
    private int threadPoolSize;

    @Autowired
    private InstrumentFileMapper instrumentFileMapper;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private BatchProgressService progressService;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ReglissRequestContext requestContext;


    public void streamAndPersist(String transformedXml, Version version, ReglissList list, long batchExecutionId) throws Exception {
        XmlMapper xmlMapper = new XmlMapper();
        log.info("Starting parallel streaming import for instrument...");

        InstrumentFilesWrapper wrapper = xmlMapper.readValue(transformedXml, InstrumentFilesWrapper.class);

        if (wrapper == null || wrapper.getInstrumentFiles() == null || wrapper.getInstrumentFiles().isEmpty()) {
            log.warn("No data found in Instrument XML after transformation.");
            return;
        }

        List<InstrumentFilesDto> allDtos = wrapper.getInstrumentFiles();
        int totalRecords = allDtos.size(); // Total to be processed

        BackpressureExecutor executor = new BackpressureExecutor("six-import", threadPoolSize, requestContext);
        List<InstrumentFile> currentBatch = new ArrayList<>(dbBatchSize);

        log.info("Processing {} instruments records in batches of {}", totalRecords, dbBatchSize);

        for (InstrumentFilesDto dto : allDtos) {
            if (dto == null) continue;

            currentBatch.add(instrumentFileMapper.mapperStructuredFile(dto));

            if (currentBatch.size() >= dbBatchSize) {
                List<InstrumentFile> batchToProcess = new ArrayList<>(currentBatch);

                executor.blockingSubmit(() -> {
                    processBatchWithProgress(batchToProcess, version, list, batchExecutionId, totalRecords);
                });

                currentBatch.clear();
            }
        }

        if (!currentBatch.isEmpty()) {
            List<InstrumentFile> finalBatch = new ArrayList<>(currentBatch);
            executor.blockingSubmit(() -> {
                processBatchWithProgress(finalBatch, version, list, batchExecutionId, totalRecords);
            });
        }

        executor.shutdownAndWaitToDie();
        log.info("Instrument import completed");
    }

    private void processBatchWithProgress(
            List<InstrumentFile> batch,
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
