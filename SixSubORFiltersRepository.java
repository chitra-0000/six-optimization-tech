package com.bnpp.regliss.repository.six;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SixSubORFiltersRepository extends EntityRepository<SixSubORFilters, Long> {

    @Modifying
    @Transactional
    @Query(nativeQuery = true, value = "update SIX_SUB_OR_FILTERS set IS_DELETED = 1 where SIX_SUBFILTER_ID = ?1")
    void deleteSubFilterId(@Param("subfilterId") Long subfilterId);
}
