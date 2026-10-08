package com.bnpp.regliss.repository.six;

import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface SixFilteredPollerRepository extends EntityRepository<SixFilteredPoller, Long> {

    /** Rows by STATUS, e.g. the FAILED / FAILED_ALL signals of SixExportRunGuard (part 3): a handful at most. */
    List<SixFilteredPoller> findByStatus(@Param("status") String status);

    /** Rows by STATUS inserted from a given time (SixExportRunGuard: failures of the running delivery only). */
    List<SixFilteredPoller> findByStatusAndInsertionTimeGreaterThanEqual(@Param("status") String status,
                                                                         @Param("insertionTime") LocalDateTime insertionTime);

    // Part 4: findBySixListReference / deleteEntriesBySixListReference removed. They read or deleted every row of a
    // list whatever its raw version (only the old SixXmlGenerationPoller used them). Rows are now removed by
    // SixFilteredStore.deleteOlderVersionRows (older raw versions of one file type only).
}
