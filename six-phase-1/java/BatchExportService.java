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
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.bnpp.regliss.entity.BatchExportType.*;      // TODO: check the package of BatchExportType / GenerationReason in the IDE
// TODO: re-add project imports for the SIX regeneration checks: ListImportNodeLockRepository, BatchJobExecutionRepository,
// SixFilteredPollerRepository, BatchJobType, ReglissException, ImportFileType
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

    @Autowired
    private ListImportNodeLockRepository listImportNodeLockRepository;

    @Autowired
    private BatchJobExecutionRepository batchJobExecutionRepository;

    @Autowired
    private SixFilteredPollerRepository sixFilteredPollerRepository;

    /** SIX raw lists whose import blocks a regeneration. */
    private static final List<ImportFileType> SIX_RAW_FILE_TYPES = Collections.unmodifiableList(Arrays.asList(
            ImportFileType.SIX_INSTRUMENTS_FILE, ImportFileType.SIX_STRUCTURED_FILE, ImportFileType.SIX_OPTIONS_FILE));
    /** A regeneration job not updated for this long is dead (same 30 minutes as the KO rule of the UI). */
    private static final long REGENERATION_ALIVE_MINUTES = 30;

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

    /**
     * Regenerate button of one SIX output list: one request per raw file (INSTR, STRUCT) on the latest raw versions.
     * Refused (ReglissException, nothing saved) when:
     *  - a SIX import is in progress (also its confidence step): a SIX raw list is locked, or the latest
     *    BATCH_IMPORT_SIXRAW job of a SIX raw list has no end date (success or failure, the delivery is not finished);
     *  - a regeneration of the same list is pending (request not yet taken) or still running.
     * The import check is done again after the versions are read: an import starting in between is refused too.
     */
    @Transactional
    public void persistSixFilteredFileGenerationStubInTx(long listId)
    {
        String launchingUser = getLaunchingUserByGenerationAction(REGENERATION);
        Optional<ReglissList> lists = reglissListRepository.findById(listId);
        if(lists.isPresent()) {
            String reference = lists.get().getReference();
            refuseIfSixImportInProgress(reference);
            refuseIfRegenerationInProgress(reference);
            Version latestInstruments = versionRepo.getLatestVersionOfInstruments();
            Version latestStructure = versionRepo.getLatestVersionOFStructure();
            refuseIfSixImportInProgress(reference);
            Long listRef = Long.valueOf(reference);
            BatchExportParams exportParamsInstr = new BatchExportParams(SIX_FILTERED_FILE_REGENERATION, Arrays.asList(listRef), launchingUser, REGENERATION, latestInstruments);
            BatchExportParams exportParamsStruct = new BatchExportParams(SIX_FILTERED_FILE_REGENERATION, Arrays.asList(listRef), launchingUser, REGENERATION, latestStructure);
            BatchExport batchExportInstr = new BatchExport(exportParamsInstr);
            BatchExport batchExportStruct = new BatchExport(exportParamsStruct);
            batchExportRepository.save(batchExportInstr);
            batchExportRepository.save(batchExportStruct);
        }
    }


    // ------------------------------------------------------------------------------------------ SIX regeneration checks

    private void refuseIfSixImportInProgress(String listReference) {
        List<Long> rawListIds = SIX_RAW_FILE_TYPES.stream()
                .flatMap(type -> reglissListRepository.findByDJFormatNotDeleted(type).stream())
                .map(ReglissList::getId)
                .collect(Collectors.toList());
        if (rawListIds.isEmpty()) {
            return;
        }
        boolean locked = listImportNodeLockRepository.countByListIdIn(rawListIds) > 0;
        boolean importOpen = rawListIds.stream()
                .map(id -> batchJobExecutionRepository.findTop1ByJobTypeAndReglissListIdOrderByStartDateDesc(BatchJobType.BATCH_IMPORT_SIXRAW, id))
                .anyMatch(job -> job.isPresent() && job.get().isNotFinished());
        if (locked || importOpen) {
            log.info("SIX regeneration of list {} refused: SIX import in progress (list locked: {}, import job open: {})",
                    listReference, locked, importOpen);
            throw new ReglissException(ReglissException.ErrorCode.SIX_IMPORT_IN_PROGRESS, listReference);
        }
    }

    private void refuseIfRegenerationInProgress(String listReference) {
        long pending = batchExportRepository.countSixRegenerationRequests(listReference);
        long running = sixFilteredPollerRepository.countRunningRegenerationRows(listReference,
                LocalDateTime.now().minusMinutes(REGENERATION_ALIVE_MINUTES));
        if (pending > 0 || running > 0) {
            log.info("SIX regeneration of list {} refused: a regeneration of this list is in progress (requests: {}, rows: {})",
                    listReference, pending, running);
            throw new ReglissException(ReglissException.ErrorCode.SIX_REGENERATION_IN_PROGRESS, listReference);
        }
    }

}
