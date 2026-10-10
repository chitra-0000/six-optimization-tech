package com.bnpp.regliss.exporter;

import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;          // use jakarta.persistence.* on Spring Boot 3
import javax.persistence.LockModeType;
import javax.persistence.LockTimeoutException;
import javax.persistence.OptimisticLockException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.bnpp.regliss.entity.BatchExportType.*;      // TODO: check the package of BatchExportType / GenerationReason in the IDE
import static com.bnpp.regliss.entity.GenerationReason.GENERATION;
import static com.bnpp.regliss.entity.GenerationReason.REGENERATION;


@Service
@Slf4j
public class BatchExportService implements IBatchExportService {

    @Autowired
    private BatchExportRepository batchExportRepository;

    @Autowired
    private ReglissRequestContext requestContext;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private VersionRepository versionRepo;

    @Autowired
    private ReglissListRepository reglissListRepository;

    @Autowired
    private NodeDetailsSupplier nodeDetailsSupplier;

    @Transactional(propagation= Propagation.REQUIRES_NEW)
    public void persistVersionExportRegenerationInTx(Long versionId, List<Long> generatedFileIds) {
        Version version = versionRepo.getExactlyOne(versionId);
        persistExportGenerationStubInTx(VERSION_GENERATED_FILE, generatedFileIds, REGENERATION, version);
    }

    @Transactional(propagation= Propagation.REQUIRES_NEW)
    public void persistConcatenatedListExportGenerationInTx(List<Long> generatedFileIds, GenerationReason reason) {
        persistExportGenerationStubInTx(CONCATENATED_LIST_GENERATED_FILE, generatedFileIds, reason, null);
}

    @Transactional(propagation= Propagation.REQUIRES_NEW)
    public void persistAddModSupExportGenerationInTx(Version version, Long addModSupId, GenerationReason reason) {
        persistExportGenerationStubInTx(ADD_MOD_SUP_FILE, Collections.singletonList(addModSupId), reason, version);
    }

    @Transactional(propagation= Propagation.REQUIRES_NEW)
    public void persistAddModSupAuditTrailGenerationInTx(Version version, Long addModSupId, GenerationReason reason) {
        persistExportGenerationStubInTx(ADD_MOD_SUP_AUDIT_TRAIL_FILE, Collections.singletonList(addModSupId), reason, version);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistDeliveryReportExportGenerationInTx(Version version, Long deliveryReportId, GenerationReason reason) {
        persistExportGenerationStubInTx(VERSION_GENERATED_FILE, Collections.singletonList(deliveryReportId), reason, version);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistVersionExportGenerationAtCertificationOrConfirmation(long versionId, FileIdsToGenerate filesToGenerate) {
        Version version = versionRepo.getExactlyOne(versionId);

        List<Long> versionFileIds = filesToGenerate.getExportFileIds();
        if(!versionFileIds.isEmpty()) {
            persistExportGenerationStubInTx(VERSION_GENERATED_FILE, versionFileIds, GENERATION, version);
        }
        if(filesToGenerate.getAddModSupFileId() != null) {
            persistExportGenerationStubInTx(ADD_MOD_SUP_FILE, Collections.singletonList(filesToGenerate.getAddModSupFileId()), GENERATION, version);
        }
    }

    private void persistExportGenerationStubInTx(BatchExportType exportType, List<Long> generatedFileIds, GenerationReason generationReason, Version version) {
        String launchingUser = getLaunchingUserByGenerationAction(generationReason);
        BatchExportParams exportParams = new BatchExportParams(exportType, generatedFileIds, launchingUser, generationReason, version);
        BatchExport batchExport = new BatchExport(exportParams);
        batchExportRepository.save(batchExport);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryLockBatchExport(Long batchExportId) {
        try {
            BatchExport batchExport = batchExportRepository.getExactlyOne(batchExportId);
            Map<String, Object> mp = new HashMap<>();
            mp.put("javax.persistence.lock.timeout", Session.LockRequest.PESSIMISTIC_NO_WAIT);
            entityManager.lock(batchExport, LockModeType.PESSIMISTIC_READ, mp);
            entityManager.refresh(batchExport);
            if (batchExport.getBatchNodeId() == null) {
                batchExport.setBatchNodeId(nodeDetailsSupplier.getNodeId());
                batchExport.setStartTime(requestContext.getRequestTime());
                return true;
            }
        } catch (LockTimeoutException e) {
            log.debug("lock ko for batch export {}", batchExportId);
        } catch (OptimisticLockException e) {
            log.debug("Could not lock batch row {}, an other node is processing it", batchExportId);
        }

        return false;
    }

    public void deleteProcessedBatchExport(Long id) { batchExportRepository.deleteById(id); }

    private String getLaunchingUserByGenerationAction(GenerationReason reason) {
        switch (reason) {
            case GENERATION:
                return User.SYS_USERNAME;
            case REGENERATION:
                return requestContext.getUsername();
            default:
                throw new IllegalArgumentException("Not a valid reason: " + reason);
        }
    }

    public void persistSixFilteredFileGenerationStubInTx(BatchExportType exportType, GenerationReason generationReason, Version version) {
        String launchingUser = getLaunchingUserByGenerationAction(generationReason);
        List<ReglissList> lists = reglissListRepository.findByDJFormatNotDeleted(ImportFileType.SIX_MAIN_FILE);
        List<Long> generatedFileIds = lists.stream().map(ReglissList::getReference).map(Long::valueOf).collect(Collectors.toList());
        BatchExportParams exportParams = new BatchExportParams(exportType, generatedFileIds, launchingUser, generationReason, version);
        BatchExport batchExport = new BatchExport(exportParams);
        batchExportRepository.save(batchExport);
    }

    public void persistSixImportedFileGenerationStubInTx(BatchExportType exportType, GenerationReason generationReason, Version version, long batchExecutionId) {
        String launchingUser = getLaunchingUserByGenerationAction(generationReason);
        List<Long> generatedFileIds = Arrays.asList(batchExecutionId);
        BatchExportParams exportParams = new BatchExportParams(exportType, generatedFileIds, launchingUser, generationReason, version);
        BatchExport batchExport = new BatchExport(exportParams);
        batchExportRepository.save(batchExport);
    }

    public void persistSixFilteredFileGenerationStubInTx(long listId)
    {
        String launchingUser = getLaunchingUserByGenerationAction(REGENERATION);
        Optional<ReglissList> lists = reglissListRepository.findById(listId);
        if(lists.isPresent()) {
            Long listRef = Long.valueOf(lists.get().getReference());
            BatchExportParams exportParamsInstr = new BatchExportParams(SIX_FILTERED_FILE_REGENERATION, Arrays.asList(listRef), launchingUser, REGENERATION, versionRepo.getLatestVersionOfInstruments());
            BatchExportParams exportParamsStruct = new BatchExportParams(SIX_FILTERED_FILE_REGENERATION, Arrays.asList(listRef), launchingUser, REGENERATION, versionRepo.getLatestVersionOFStructure());
            BatchExport batchExportInstr = new BatchExport(exportParamsInstr);
            BatchExport batchExportStruct = new BatchExport(exportParamsStruct);
            batchExportRepository.save(batchExportInstr);
            batchExportRepository.save(batchExportStruct);
        }
    }

}
