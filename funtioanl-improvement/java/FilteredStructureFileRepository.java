package com.bnpp.regliss.repository.six;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.QueryHint;          // use jakarta.persistence.QueryHint if the project is on Spring Boot 3
import java.util.List;

// TODO: re-add project imports (Alt+Enter / Optimize Imports) for: EntityRepository, FilteredStructuredFile

public interface FilteredStructureFileRepository extends EntityRepository<FilteredStructuredFile, Long>, JpaRepository<FilteredStructuredFile, Long> {

    @Query(value = "SELECT f.* FROM FILTERED_SIX_STRUCTURED f JOIN (SELECT SIX_LIST_REF, MAX(VERSION_ID) AS MAX_VER FROM FILTERED_SIX_STRUCTURED " +
            "WHERE SIX_LIST_REF = :reference GROUP BY SIX_LIST_REF) mv ON f.SIX_LIST_REF = mv.SIX_LIST_REF AND f.VERSION_ID = mv.MAX_VER", nativeQuery = true)
    @QueryHints({
            @QueryHint(name = "org.hibernate.fetchSize", value = "1000"),
            @QueryHint(name = "org.hibernate.readOnly", value = "true")
    })
    List<FilteredStructuredFile> getByListRef(@Param("reference") String reference);

    @Modifying @Transactional
    @Query(value =
            "DELETE /*+ PARALLEL(f 8) */ " +
                    "FROM   FILTERED_SIX_STRUCTURED f "+
                    "WHERE  f.VERSION_ID = :v "+
                    " AND  f.SIX_LIST_REF = :ref "+
                    "AND  ROWNUM <= :batch "
            , nativeQuery = true)
    int deleteStrucutBatch(@Param("v") Long versionId,
                           @Param("ref") String listRef,
                           @Param("batch") int batchSize);
    @Modifying @Transactional
    @Query(value =
            "DELETE /*+ PARALLEL(f 8) */ " +
                    "FROM   FILTERED_SIX_STRUCTURED f "+
                    " WHERE  f.SIX_LIST_REF = :ref "+
                    "AND  ROWNUM <= :batch "
            , nativeQuery = true)
    int deleteByListRef(@Param("ref") String listRef,
                        @Param("batch") int batchSize);

    /**
     * Rows of the NEWEST version of one output list WITH their targets, in ONE query (part 3).
     * getByListRef (native query) returned the parents only; the EAGER sixTargets were then loaded with one extra
     * query per parent (N+1: one lakh parents = one lakh queries). Same rows as getByListRef (MAX(VERSION_ID) of
     * the list ref), ordered by id so duplicated ISINs are merged in the same order as before.
     */
    @Query("SELECT DISTINCT f FROM FilteredStructuredFile f LEFT JOIN FETCH f.sixTargets t " +
            "WHERE f.listRef = :reference " +
            "AND f.version.id = (SELECT MAX(g.version.id) FROM FilteredStructuredFile g WHERE g.listRef = :reference) " +
            "ORDER BY f.id, t.id")
    @QueryHints({
            @QueryHint(name = "hibernate.query.passDistinctThrough", value = "false"),
            @QueryHint(name = "org.hibernate.fetchSize", value = "1000"),
            @QueryHint(name = "org.hibernate.readOnly", value = "true")
    })
    List<FilteredStructuredFile> findLatestByListRefWithTargets(@Param("reference") String reference);

    /**
     * Export phase 1, step 1 of the read of the XML step: the newest VERSION_ID of one output list (null: no rows).
     * Same MAX as the sub-query of findLatestByListRefWithTargets.
     */
    @Query("SELECT MAX(g.version.id) FROM FilteredStructuredFile g WHERE g.listRef = :reference")
    Long findLatestVersionIdByListRef(@Param("reference") String reference);

    /**
     * Export phase 1, step 2: the rows of that version WITH their targets, in ONE query. Same select, same hints and same
     * order (f.id, t.id) as findLatestByListRefWithTargets; the version is given instead of the MAX sub-query, so the
     * (VERSION_ID, SIX_LIST_REF) index is used directly.
     */
    @Query("SELECT DISTINCT f FROM FilteredStructuredFile f LEFT JOIN FETCH f.sixTargets t " +
            "WHERE f.listRef = :reference " +
            "AND f.version.id = :versionId " +
            "ORDER BY f.id, t.id")
    @QueryHints({
            @QueryHint(name = "hibernate.query.passDistinctThrough", value = "false"),
            @QueryHint(name = "org.hibernate.fetchSize", value = "1000"),
            @QueryHint(name = "org.hibernate.readOnly", value = "true")
    })
    List<FilteredStructuredFile> findByVersionAndListRefWithTargets(@Param("versionId") Long versionId, @Param("reference") String reference);
}
