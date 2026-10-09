    // ==== RecordRepository.java (regliss-domain-jar, package com.bnpp.regliss.repository) ====
    // ONLY the part visible in the screenshot. The start of the first query and the end of the last
    // native query were cut off - the "..." lines are TODO, copy them from the IDE.

    // ... (query start cut off)
            + " INNER JOIN Version v on v.id = rr.versionLinks.clonedInVersionId "
            + " WHERE r.id IN ?1")
    List<Object[]> getHistoricalVersionsForRecordIds_updatedRaw(List<Long> ids);

    @Query("SELECT r.id, v.versionNumber, v.startDate"
            + " FROM Record r "
            + " INNER JOIN Version v ON v.id = r.versionLinks.createdInVersionId "
            + " WHERE r.id IN ?1")
    List<Object[]> getHistoricalVersionsForRecordIds_createdRaw(List<Long> ids);

    @Query("SELECT r FROM Record r JOIN Version v ON r.versionLinks.listId=v.list.id and v.id=?2 AND r.versionLinks.fromVersionId <= ?2 AND r.versionLinks.toVersionId >= ?2 WHERE r.externalUuid = ?1  ")
    Optional<Record> findByUUIDAndVersionId(String uuid, Long versionId);

    @Query("SELECT r FROM Record r JOIN Version v ON r.versionLinks.listId=v.list.id and v.id=?2 AND r.versionLinks.fromVersionId <= ?2 AND r.versionLinks.toVersionId >= ?2 WHERE r.externalReference = ?1  ")
    Optional<Record> findByExternalReferenceAndVersionId(String externalReference, Long versionId);
    @Query("SELECT r FROM Record r JOIN Version v  ON r.versionLinks.listId=v.list.id AND r.versionLinks.fromVersionId <= v.id AND r.versionLinks.toVersionId >= v.id JOIN v.list lst " +
            " WHERE r.recordId = ?1 AND v.versionNumber = ?2 and lst.reference=?3 and v.status<>com.bnpp.regliss.entity.VersionStatus.DELETED")
    Optional<Record> findByRecordIdAndVersionNoAndListReference(String recordId, Long versionNo,String listReference);

    @Query("SELECT r FROM Record r JOIN Version v  ON r.versionLinks.listId=v.list.id AND r.versionLinks.fromVersionId <= v.id AND r.versionLinks.toVersionId >= v.id JOIN v.list lst WHERE r.externalReference = ?1 ...")   // TODO: rest of this query is off-screen
    Optional<Record> findByRecordExternalRefferenceAndVersionNoAndListReference(String recordExtRef, Long versionNo,String listReference);

    Optional<Record> findByExternalUuid(String uuid);

    @Query("SELECT r FROM Record r WHERE r.externalUuid = ?1 "
            + " AND r.versionLinks.listId = ?2 ")
    Optional<Record> findByExternalUuidAndListId(String uuid, long listId);

    @Query("SELECT r FROM Record r WHERE r.reglissReference = ?1 "
            + " AND r.versionLinks.fromVersionId <=?2 "
            + " AND r.versionLinks.toVersionId >= ?2 "
            + " AND r.versionLinks.listId = ?3 ")
    Optional<Record> findByReglissReferenceAndVersionIdAndListId(String reglissrefernce, Long versionId, Long listId);

    @Query(nativeQuery = true, value = "SELECT v.certification_date, v.file_name "
            + "FROM record r "
            + "LEFT OUTER JOIN version v ON r.DELETED_IN_VERSION = v.id AND r.list_id=v.list_id "
            + "LEFT OUTER JOIN version_dj vdj ON v.version_dj_id =vdj.id "
            + "WHERE r.list_id= ?2  "
            + "AND v.certification_date is not null "
    // ... TODO: rest of this native query is off-screen
