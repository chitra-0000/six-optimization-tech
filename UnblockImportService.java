package com.bnpp.regliss.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static com.bnpp.regliss.entity.UploadStatus.IMPORTED_OK;   // TODO: check the package of UploadStatus in the IDE
import static com.bnpp.regliss.entity.UploadStatus.IMPORT_KO;

@Slf4j
@Service
public class UnblockImportService {
    @Autowired
    private ImportedFileService importedFileService;
    @Autowired
    private BatchManagementService batchManagementService;
    @Autowired
    private VersionLockService versionLockService;
    @Autowired
    private EmailService emailService;
    @Autowired
    private VersionService versionService;
    @Autowired
    private BatchJobExecutionRepository batchJobExecutionRepository;
    @Autowired
    private ImportedFileRepository importedFileRepository;
    @Autowired
    private ReglissListRepository reglissListRepository;
    @Autowired
    private ManualImportFileRepository fileRepository;
    @Autowired
    private NodeDetailsSupplier nodeDetailsSupplier;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ReglissRequestContext requestContext;

    @Transactional
    public void finishImportExecutionByRequesterUser(Long batchJobExecutionId) {
        BatchJobExecution batchJobExecution = batchJobExecutionRepository.getExactlyOne(batchJobExecutionId);
        User user = userRepository.getExactlyOne(requestContext.getUserId());
        tryMarkBatchExecutionFinished(batchJobExecutionId);
        tryRemoveListNodeLock(batchJobExecution.getReglissListId(), batchJobExecution.getNodeId(),
                              user.getUsername(), getVersionId(batchJobExecution.getVersion()));
    }

    @Transactional
    public void finishExportExecutionByRequesterUser(Long batchJobExecutionId) {
        tryMarkBatchExecutionFinished(batchJobExecutionId);
    }

    @Transactional
    public void unblockSemiAutoImportRegardlessTheStatusInTx(Long batchJobExecutionId, Long lastUploadedFileId) {
        BatchJobExecution batchJobExecution = batchJobExecutionRepository.getExactlyOne(batchJobExecutionId);
        ImportedFile lastUploadedFile = importedFileRepository.getExactlyOne(lastUploadedFileId);

        tryUnlockSemiAutoVersionInTx(batchJobExecution.getVersion().getId());
        trySetUploadedFileKOStatusOnPrematureTerminationInTx(lastUploadedFile.getId());
        tryDeleteSemiAutoFileFromDisk(lastUploadedFile.getFileName());
    }

    @Transactional
    public void unblockSemiAutoImportWhenNoFeedInTx(Long uploadedFileId, List<ImportLineErrorReport> errors) {
        ImportedFile importedFile = importedFileRepository.getExactlyOne(uploadedFileId);
        Version version = importedFile.getVersion();

        importedFileService.setSemiAutoImportedKOInTx(importedFile.getId(), errors);
        tryRemoveListNodeLock(version.getList().getId(), nodeDetailsSupplier.getNodeId(), importedFile.getUserName(), version.getId());
        tryUnlockSemiAutoVersionInTx(version.getId());
        tryDeleteSemiAutoFileFromDisk(importedFile.getFileName());
    }

    public void tryDeleteAutomaticVersionAndRecordsInTx(Long versionId) {
        try {
            versionService.deleteVersionAndRecordsFromDBInTx(versionId);
        } catch (Exception e) {
            log.error("Could not delete version with id {} on auto import {}", versionId, e.getMessage());
        }
    }

    public void tryRevertSemiAutoVersionInTx(Long versionId) {
        try {
            versionService.revertVersionInTx(versionId);
        } catch (Exception e) {
            log.error("Could not unlock version with id {} on semiauto import {}", versionId, e.getMessage());
            log.error("revert version",e);
        }
    }

    public void tryRevertSemiAutoVersionInTx(Long versionId,Integer versionDeleteTimeout) {
        try {
            versionService.revertVersionInTx(versionId,versionDeleteTimeout);
        } catch (Exception e) {
            log.error("Could not unlock version with id {} on semiauto import {}", versionId, e.getMessage());
            log.error("revert version",e);
        }
    }

    private void trySetUploadedFileKOStatusOnPrematureTerminationInTx(Long uploadedFileId) {
        try {
            UploadStatus status = importedFileService.getByFileId(uploadedFileId).getStatus();
            if (IMPORTED_OK != status && IMPORT_KO != status) {
                importedFileService.setImportedKOInTx(uploadedFileId);
            }
        } catch (Exception e) {
            log.error("Could not set upload status after semiauto import {}", e.getMessage());
        }
    }

    private void tryMarkBatchExecutionFinished(Long batchExecutionId) {
        try {
            batchManagementService.markBatchExecutionFinishedByRequester(batchExecutionId);
        } catch (Exception e) {
            log.error("Could not update finished batchExecution {}", e.getMessage());
        }
    }

    public void tryUnlockSemiAutoVersionInTx(Long versionId) {
        try {
            //unlock version regardless of any exception
            versionLockService.unlockSemiAutoVersionInTx(versionId);
        } catch (Exception e) {
            log.error("Could not unlock version on semiauto upload {}", e.getMessage());
        }
    }

    private void tryRemoveListNodeLock(Long listId, String nodeId, String userName, Long versionId) {
        ReglissList reglissList = reglissListRepository.getExactlyOne(listId);
        try {
            batchManagementService.attemptUnlockListWithNode(reglissList, nodeId);
        } catch (Exception e) {
            log.error("Could not remove list lock ", e);
            emailService.sendEmailUnLockListKO(reglissList, userName, versionId);
        }
    }

    private void tryDeleteSemiAutoFileFromDisk(String uploadedFileName) {
        try {
            log.info("Delete file with name {} ", uploadedFileName);
            fileRepository.deleteManualImportedFileFromDisk(uploadedFileName);
        }catch(Exception e){
            log.error("Could not delete file with name {} on auto import {}", uploadedFileName, e.getMessage());
        }
    }

    private Long getVersionId(Version version){
        if(null == version)
            return null;
        else
            return version.getId();
    }

    // TODO: lines 176-206 below were read from a BLURRED photo - verify every line marked "verify" against the IDE
    @Transactional
    public void finishSixImportExecutionByRequesterUser(Long batchJobExecutionId, String launchUserName) {
        BatchJobExecution batchJobExecution = batchJobExecutionRepository.getExactlyOne(batchJobExecutionId);
        User user = userRepository.findByUsername(launchUserName);
        try {
            log.info("Marking batch execution finished");
            LocalDateTime now = LocalDateTime.now();
            batchJobExecution.setEndDate(now);                       // verify
            batchJobExecution.setLastUpdateDate(now);                // verify
            batchJobExecution.setUnblockingUserId(user.getId());     // verify
        } catch (Exception e) {
            log.error("Could not update finished batchExecution {}", e.getMessage());
        }

        tryRemoveListNodeLock(batchJobExecution.getReglissListId(), batchJobExecution.getNodeId(),
                launchUserName, getVersionId(batchJobExecution.getVersion()));
    }

    public void tryDeleteSixAutomaticVersionAndRecordsInTx(Long versionId) {
        try {
            versionService.deleteVersionAndRecordsFromDBInSix(versionId);    // verify method name
        } catch (Exception e) {
            log.error("Could not delete six version with id {} on auto import {}", versionId, e.getMessage());
        }
    }

    public void tryDeleteNonLatestSixVersionRecords(Long versionId) {
        try {
            versionService.deleteNonLatestSixRecordsFromDB(versionId);       // verify method name
        } catch (Exception e) {
            log.error("Could not delete old six records with version id {} on auto import {}", versionId, e.getMessage());
        }
    }
}
