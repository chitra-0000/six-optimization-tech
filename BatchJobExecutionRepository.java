package com.bnpp.regliss.repository.batch;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BatchJobExecutionRepository extends EntityRepository<BatchJobExecution, Long>, BatchJobExecutionRepositoryCustom {

    @Modifying()
    @Query("UPDATE BatchJobExecution b set b.percent=b.percent+:incrementPercent, b.lastUpdateDate=:updateTime where b.id=:batchExecutionId")
    void incrementPercent(@Param("batchExecutionId") long batchExecutionId, @Param("incrementPercent") float incrementPercent, @Param("updateTime") LocalDateTime updateTime);

    List<BatchJobExecution> findByNodeIdAndJobTypeInAndEndDateIsNull(String nodeId, List<BatchJobType> batchJobType);

    List<BatchJobExecution> findByNodeId(String nodeId);

    List<BatchJobExecution> findByVersion(Version version);

    @Query("SELECT be from BatchJobExecution be where be.id = " +
            "(select MAX(be1.id) from BatchJobExecution be1 where be1.endDate = "
            + "(select MAX(be2.endDate) from BatchJobExecution be2 where be2.jobType = 'BATCH_EXPORT' and be2.jobParams LIKE :fileType)" +
            ") and be.unblockingUserId = :sysUserId")
    Optional<BatchJobExecution> getLastExportedJobByType(@Param("fileType") String fileType, @Param("sysUserId") Long sysUserId);

    @Query("SELECT be from BatchJobExecution be where be.id = " +
            "(select MAX(be1.id) from BatchJobExecution be1 where be1.endDate = "
            + "(select MAX(be2.endDate) from BatchJobExecution be2 where be2.jobType = 'BATCH_EXPORT' and be2.jobParams NOT LIKE :fileType1 and be2.jobParams NOT LIKE :fileType2)" +
            ") and be.unblockingUserId = :sysUserId")
    Optional<BatchJobExecution> getLastExportedJobOtherThanType(@Param("fileType1") String fileType1, @Param("fileType2") String fileType2, @Param("sysUserId") Long sysUserId);

    Optional<BatchJobExecution> findTop1ByVersionOrderByStartDateDesc(Version version);

    @Query("SELECT e FROM BatchJobExecution e where e.startDate >= :periodStartDate")
    List<BatchJobExecution> getAllBatchJobExecutionsNoPagination(@Param("periodStartDate") LocalDateTime periodStartDate);

    @Query("SELECT COUNT(e) from BatchJobExecution e WHERE e.startDate>= :periodStartDate AND (:jobTypesEmpty = true OR e.jobType IN :jobTypes) AND (:dateFrom IS NULL OR e.startDate >= :dateFrom) " +
            "AND (:dateTo IS NULL OR e.startDate <= :dateTo) AND ((:includeOk = true AND e.endDate IS NOT NULL) OR (:includeInProgress = true AND e.endDate IS NULL AND " +
            "(NOT EXISTS (SELECT 1 FROM Heartbeat h WHERE h.nodeId = e.nodeId) OR (SELECT h.lastBeat FROM Heartbeat h WHERE h.nodeId = e.nodeId) > :heartbeatCutoffTime) " +
            "AND e.lastUpdateDate > :jobCutoffTime) OR (:includeKo = true and e.endDate IS NULL AND NOT ((NOT EXISTS (SELECT 1 FROM Heartbeat h where h.nodeId = e.nodeId) " +
            "OR (SELECT h.lastBeat FROM Heartbeat h WHERE h.nodeId = e.nodeId) > :heartbeatCutoffTime) AND e.lastUpdateDate > :jobCutoffTime)))ORDER BY e.startDate Desc")
    Long countAllBatchJobExecutions(@Param("periodStartDate") LocalDateTime periodStartDate, @Param("jobTypes") List<BatchJobType> jobTypes, @Param("jobTypesEmpty") boolean jobTypesEmpty,
                                    @Param("includeOk") boolean includeOk, @Param("includeInProgress") boolean includeInProgress, @Param("includeKo") boolean includeKo,
                                    @Param("dateFrom") LocalDateTime dateFrom, @Param("dateTo") LocalDateTime dateTo
            ,@Param("heartbeatCutoffTime") LocalDateTime heartbeatCutoffTime,
                                    @Param("jobCutoffTime") LocalDateTime jobCutoffTime
    );


    @Query("SELECT e from BatchJobExecution e WHERE e.startDate>= :periodStartDate AND (:jobTypesEmpty = true OR e.jobType IN :jobTypes) AND (:dateFrom IS NULL OR e.startDate >= :dateFrom) " +
            "AND (:dateTo IS NULL OR e.startDate <= :dateTo) AND ((:includeOk = true AND e.endDate IS NOT NULL) OR (:includeInProgress = true AND e.endDate IS NULL " +
            "AND (NOT EXISTS (SELECT 1 FROM Heartbeat h WHERE h.nodeId = e.nodeId) OR (SELECT h.lastBeat FROM Heartbeat h WHERE h.nodeId = e.nodeId) > :heartbeatCutoffTime) " +
            "AND e.lastUpdateDate > :jobCutoffTime) OR (:includeKo = true and e.endDate IS NULL AND NOT ((NOT EXISTS (SELECT 1 FROM Heartbeat h where h.nodeId = e.nodeId) " +
            "OR (SELECT h.lastBeat FROM Heartbeat h WHERE h.nodeId = e.nodeId) > :heartbeatCutoffTime) AND e.lastUpdateDate > :jobCutoffTime))ORDER BY e.startDate Desc")
    List<BatchJobExecution> getAllBatchJobExecutionsWithPagination(@Param("periodStartDate") LocalDateTime periodStartDate, @Param("jobTypes") List<BatchJobType> jobTypes, @Param("jobTypesEmpty") boolean jobTypesEmpty,
                                                                   @Param("includeOk") boolean includeOk, @Param("includeInProgress") boolean includeInProgress, @Param("includeKo") boolean includeKo,
                                                                   @Param("dateFrom") LocalDateTime dateFrom, @Param("dateTo") LocalDateTime dateTo, Pageable pageable
            ,@Param("heartbeatCutoffTime") LocalDateTime heartbeatCutoffTime,
                                                                   @Param("jobCutoffTime") LocalDateTime jobCutoffTime
    );

    List<BatchJobExecution> findByVersionAndJobType(Version version, BatchJobType batchJobType);
}
