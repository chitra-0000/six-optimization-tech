package com.bnpp.regliss.repository.six;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.QueryHint;          // use jakarta.persistence.QueryHint if the project is on Spring Boot 3
import java.util.List;

// TODO: re-add project imports (Alt+Enter / Optimize Imports) for: EntityRepository, FilteredInstrumentFile

public interface FilteredInstrumentFileRepository extends EntityRepository<FilteredInstrumentFile, Long> , JpaRepository<FilteredInstrumentFile, Long> {

    @Query(value = "SELECT f.* FROM FILTERED_SIX_INSTRUMENTS f JOIN (SELECT SIX_LIST_REF, MAX(VERSION_ID) AS MAX_VER FROM FILTERED_SIX_INSTRUMENTS " +
            "WHERE SIX_LIST_REF = :reference GROUP BY SIX_LIST_REF) mv ON f.SIX_LIST_REF = mv.SIX_LIST_REF AND f.VERSION_ID = mv.MAX_VER", nativeQuery = true)
    @QueryHints({
            @QueryHint(name = "org.hibernate.fetchSize", value = "1000"),
            @QueryHint(name = "org.hibernate.readOnly", value = "true")
    })
    List<FilteredInstrumentFile> getByListRef(@Param("reference") String reference);

    @Modifying @Transactional
    @Query(value =
        "DELETE /*+ PARALLEL(f 8) */ " +
        "FROM   FILTERED_SIX_INSTRUMENTS f "+
         " WHERE  f.SIX_LIST_REF = :ref "+
          "AND  ROWNUM <= :batch "
        , nativeQuery = true)
    int deleteByListRef(@Param("ref") String listRef,
                                @Param("batch") int batchSize);

    @Modifying @Transactional
    @Query(value =
        "DELETE /*+ PARALLEL(f 8) */ " +
        "FROM   FILTERED_SIX_INSTRUMENTS f "+
        "WHERE  f.VERSION_ID = :v "+
         " AND  f.SIX_LIST_REF = :ref "+
          "AND  ROWNUM <= :batch "
        , nativeQuery = true)
    int deleteInstrumentBatch(@Param("v") Long versionId,
                              @Param("ref") String listRef,
                              @Param("batch") int batchSize);

    /**
     * Rows of the NEWEST version of one output list WITH their targets, in ONE query (part 3).
     * getByListRef (native query) returned the parents only; the EAGER sixTargets were then loaded with one extra
     * query per parent (N+1: one lakh parents = one lakh queries). Same rows as getByListRef (MAX(VERSION_ID) of
     * the list ref), ordered by id so duplicated ISINs are merged in the same order as before.
     */
    @Query("SELECT DISTINCT f FROM FilteredInstrumentFile f LEFT JOIN FETCH f.sixTargets t " +
            "WHERE f.listRef = :reference " +
            "AND f.version.id = (SELECT MAX(g.version.id) FROM FilteredInstrumentFile g WHERE g.listRef = :reference) " +
            "ORDER BY f.id, t.id")
    @QueryHints({
            @QueryHint(name = "hibernate.query.passDistinctThrough", value = "false"),
            @QueryHint(name = "org.hibernate.fetchSize", value = "1000"),
            @QueryHint(name = "org.hibernate.readOnly", value = "true")
    })
    List<FilteredInstrumentFile> findLatestByListRefWithTargets(@Param("reference") String reference);
}
