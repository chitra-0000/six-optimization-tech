package com.bnpp.regliss.repository.six;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface SixFilteredPollerRepository extends EntityRepository<SixFilteredPoller, Long> {

    /** Rows by STATUS, e.g. the FAILED / FAILED_ALL signals of SixExportRunGuard (part 3): a handful at most. */
    List<SixFilteredPoller> findByStatus(@Param("status") String status);

    /** Rows by STATUS inserted from a given time (SixExportRunGuard: failures of the running delivery only). */
    List<SixFilteredPoller> findByStatusAndInsertionTimeGreaterThanEqual(@Param("status") String status,
                                                                         @Param("insertionTime") LocalDateTime insertionTime);

    /**
     * SIX regeneration of one output list still running: its REGENERATION rows that are BUILT (file waiting for its
     * release), BUILDING refreshed since {@code since}, or PENDING / READY of a job not finished and updated since
     * {@code since}. A failed list is DROPPED and a crashed one turns stale: neither blocks the next regeneration.
     */
    @Query(nativeQuery = true, value = "SELECT COUNT(*) FROM SIX_FILTERED_POLLER p"
            + " LEFT JOIN CTR_BATCH_JOB_EXECUTION j ON j.ID = p.BATCH_JOB_EXECUTION_ID"
            + " WHERE p.SIX_LIST_REFERENCE = :listRef AND p.GENERATION_REASON = 'REGENERATION'"
            + " AND (p.STATUS = 'BUILT'"
            + "   OR (p.STATUS = 'BUILDING' AND p.UPDATED_TIME > :since)"
            + "   OR (p.STATUS IN ('PENDING', 'READY') AND j.END_DATE IS NULL AND j.LAST_UPDATE_DATE > :since))")
    long countRunningRegenerationRows(@Param("listRef") String listRef, @Param("since") LocalDateTime since);

    // Part 4: findBySixListReference / deleteEntriesBySixListReference removed. They read or deleted every row of a
    // list whatever its raw version (only the old SixXmlGenerationPoller used them). Rows are now removed by
    // SixFilteredStore.deleteOlderVersionRows (older raw versions of one file type only).
}
