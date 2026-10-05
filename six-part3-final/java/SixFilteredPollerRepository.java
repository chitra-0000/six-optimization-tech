package com.bnpp.regliss.repository.six;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface SixFilteredPollerRepository extends EntityRepository<SixFilteredPoller, Long> {

    /** Rows by STATUS, e.g. the FAILED / FAILED_ALL signals of SixExportRunGuard (part 3): a handful at most. */
    List<SixFilteredPoller> findByStatus(@Param("status") String status);

    List<SixFilteredPoller> findBySixListReference(@Param("sixListReference") String sixListReference);
    @Modifying
    @Transactional
    @Query(value= " DELETE FROM SIX_FILTERED_POLLER s WHERE s.SIX_LIST_REFERENCE = :reference", nativeQuery= true)
    void deleteEntriesBySixListReference(@Param("reference") String reference);
}
