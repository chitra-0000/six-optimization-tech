package com.bnpp.regliss.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface VersionRepository extends EntityRepository<Version, Long>, VersionRepositoryCustom{

    @Query("SELECT v FROM Version v "
            + "JOIN v.list l "
            + "WHERE l.globalPlusOfac = true "
            + "AND v.certificationDate != null "
            + "AND v.versionNumber = "
                + "(SELECT MAX(v2.versionNumber) "
                + "FROM l.versions v2 "
                + "WHERE v2.certificationDate != null) ")
    List<Version> getAllLatestOfacVersions();

    @Query("SELECT v FROM Version v "
            + "JOIN v.list l "
            + "WHERE l.id IN ?1 "
            + "AND v.certificationDate != null "
            + "AND v.versionNumber = "
            + "(SELECT MAX(v2.versionNumber) "
            + "FROM l.versions v2 "
            + "WHERE v2.certificationDate != null) ")
    List<Version> getAllLatestConcatenatedListVersions(List<Long> reglissListIds);

    @Query("SELECT cl.id, v FROM Version v "
            + "JOIN v.list l "
            + "INNER JOIN l.concatenatedLists cl "
            + "WHERE v.certificationDate != null "
            + "AND cl.deleted = 0 AND cl.active = 1"
            + "AND v.versionNumber = "
            + "(SELECT MAX(v2.versionNumber) "
            + "FROM l.versions v2 "
            + "WHERE v2.certificationDate != null) ")
    List<Object[]> getAllLatestVersionsByConcatenatedList();

    @Query("SELECT Distinct v FROM Version v "
            + "JOIN v.list l "
            + "WHERE l.id != ?1 "
            + "AND v.publicationDate != null "
            + "AND v.versionNumber = "
                + "(SELECT MAX(v2.versionNumber) "
                + "FROM l.versions v2 "
                + "WHERE v2.publicationDate != null) ")
    List<Version> getAllOtherLatestPublishedVersions(Long excludedListId);

    @Query("SELECT new com.bnpp.regliss.service.permission.VersionPermissionRequest("
            + "v.list.id, v.list.confidentiality, v.list.scope, v.list.country.id, v.certificationDate, v.publicationDate, v.status "
            + ") FROM Version v WHERE v.id = ?1")
    VersionPermissionRequest getVersionPermissionRequest(long versionId);

    List<Version> findByCertificationDateAfter(LocalDateTime lastOfacDeliveryDate);

    List<Version> findByStatusIn(List<VersionStatus> asList);

    Optional<Version> findById(long id);

    @Modifying
    @Query(nativeQuery = true, value="DELETE FROM Version v WHERE  v.id =?1")
    void deleteVersion(Long versionId);

    @Query("SELECT v FROM Version v WHERE v.id = (SELECT MAX(vv.id) FROM Version vv WHERE vv.list.id = ?1 AND vv.status != 'DELETED' AND vv.versionNumber < ?2)")
    Optional<Version> getPreviousVersionByNumber(long listId, long versionNumber);

    @Query("SELECT v FROM Version v WHERE v.id = (SELECT MAX(vv.id) FROM Version vv WHERE vv.list.id = ?1 AND vv.status != 'DELETED' AND vv.id < ?2)")
    Optional<Version> getPreviousVersionById(long listId, long versionId);

    @Query(nativeQuery=true,value="select LIST_ID, VERSION_ID, VERSION_NUMBER from LIST_LAST_CERTIFIED_VERSION")
    List<Object[]> getAllLatestCertifiedVersions();

    @Query("SELECT v FROM Version v WHERE v.versionNumber = ?1 and v.list.id = ?2 and v.status<>com.bnpp.regliss.entity.VersionStatus.DELETED")
    Optional<Version> getByVersionNumberAndListId(long versionNumber,long listId);

    @Query("SELECT v FROM Version v JOIN v.list lst WHERE v.versionNumber = ?1 and lst.shortName=?2 and v.status<>com.bnpp.regliss.entity.VersionStatus.DELETED")
    Optional<Version> getByVersionNumberAndListLabel(long versionNumber,String listShortName);

    @Query("SELECT v FROM Version v JOIN FETCH v.list lst WHERE v.versionNumber = ?1 and lst.reference=?2 and v.status<>com.bnpp.regliss.entity.VersionStatus.DELETED")
    Optional<Version> getByVersionNumberAndListReference(long versionNumber, String listReference);

    @Query(nativeQuery = true, value="SELECT v.id as versionId,v.publication_date as publicationDate,v.version_number as versionNumber,v.start_date as startDate,l.id as listId,l.name as listName," +
            "l.import_file_type as listFormat,l.reference as listReference FROM Version v JOIN Regliss_list l on v.list_id=l.id WHERE v.version_Number in ?1 and l.reference in ?2 " +
            "and v.status<>'DELETED' and (v.publication_date is not null) and l.is_deleted<>1")
    List<ListVersionDTO> getAllByVersionAndListReference(Set<Long> versionNumbers, Set<String> listReferences);

    @Query("select v from Version v join fetch v.list l where v.certificationDate IS NOT NULL AND v.status <> 'DELETED'and v.versionNumber in ?1 and l.reference in ?2")
    List<Version> getAllByVersionAndListReferenceVO(Set<Long> versionNumbers,Set<String> listReferences);

    @Query(nativeQuery = true, value="SELECT v.id as versionId,v.publication_date as publicationDate,v.version_number as versionNumber,v.start_date as startDate,l.id as listId,l.name as listName," +
            "l.import_file_type as listFormat,l.reference as listReference FROM Version v JOIN Regliss_list l on v.list_id=l.id WHERE v.version_Number = ?1 and l.reference=?2 and v.status<>'DELETED' and l.is_deleted<>1")
    Optional<ListVersionDTO> getVLDataByVersionNumberAndListReference(long versionNumber, String listReference);

    @Query(nativeQuery = true, value="SELECT v.id as id, v.version_number as versionNumber, v.publication_date as publicationDate, v.certification_date as certificationDate, " +
            "v.start_date as startDate, u.username as username, v.status as status, i.upload_type as uploadType FROM Version v " +
            "JOIN Users u ON v.creation_user_id = u.id LEFT OUTER JOIN Imported_file i ON v.id = i.version_id and i.upload_time=" +
            "(select max(ii.upload_time) from imported_file ii where ii.version_id=v.id and ii.status='IMPORTED_OK') WHERE v.list_id = ?1 AND v.status != 'DELETED'")
    List<ListVersionVO> getAllVersionDtoByListId(long listId);

    @Query(nativeQuery = true, value="SELECT v.id as id, v.version_number as versionNumber, v.publication_date as publicationDate, v.certification_date as certificationDate, " +
            "v.start_date as startDate, u.username as username, v.status as status, i.upload_type as uploadType, " +
            "v.added_record_number as created, v.mod_rec_no_for_synth as updated, v.sup_record_number as deleted, v.total_record_number as recordNumber  FROM Version v " +
            "JOIN Users u ON v.creation_user_id = u.id LEFT OUTER JOIN Imported_file i ON v.id = i.version_id and i.upload_time=" +
            "(select max(ii.upload_time) from imported_file ii where ii.version_id=v.id and ii.status='IMPORTED_OK') WHERE v.list_id = ?1 AND v.status != 'DELETED'")
    List<ListVersionVO> getAllVersionForVersionPageByListId(long listId);

    @Query("SELECT version.list as list, version.versionNumber as versionNumber FROM Version version WHERE version.id=?1")
    Optional<ListVersionBreadcrumbDto> getBreadcrumbForVersion(long versionId);

    @Query("SELECT MAX(version.id) FROM Version version WHERE version.list.id=?1 and version.startDate<=?2 and version.status<>'DELETED' and version.status<>'DRAFT'")
    Optional<Long> getVersionByDate(Long listId, LocalDate dateTime);

    @Query(nativeQuery = true, value="SELECT v.id as id, v.version_number as versionNumber from Version v WHERE v.list_id = ?1 AND v.status != 'DELETED'")
    List<ListVersionVO> getAllVersionNumbersByListId(long listId);

    @Query("SELECT v from Version v where v.importFileType = 'SIX_INSTRUMENTS_FILE' and v.id = (SELECT MAX(v.id) from Version v where v.importFileType = 'SIX_INSTRUMENTS_FILE')")
    Version getLatestVersionOfInstruments();

    @Query("SELECT v from Version v where v.importFileType = 'SIX_STRUCTURED_FILE' and v.id = (SELECT MAX(v.id) from Version v where v.importFileType = 'SIX_STRUCTURED_FILE') ")
    Version getLatestVersionOFStructure();

}
