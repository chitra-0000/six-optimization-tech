package com.bnpp.regliss.repository.six;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;   // was missing in the IDE ("Cannot resolve symbol 'Transactional'")

import java.util.List;

// TODO: re-add project imports (Alt+Enter / Optimize Imports) for: EntityRepository, SixFilteredPoller

public interface SixFilteredPollerRepository extends EntityRepository<SixFilteredPoller, Long> {

    List<SixFilteredPoller> findBySixListReference(@Param("sixListReference") String sixListReference);
    @Modifying
    @Transactional
    @Query(value= " DELETE FROM SIX_FILTERED_POLLER s WHERE s.SIX_LIST_REFERENCE = :reference", nativeQuery= true)
    void deleteEntriesBySixListReference(@Param("reference") String reference);
}
