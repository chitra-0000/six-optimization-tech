package com.bnpp.regliss.repository.six;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SixFiltersRepository extends EntityRepository<SixFilters, Long> {
    List<SixFilters> findByActiveTrue();

    List<SixFilters> getFiltersByListIdAndDeletedFalse(Long listId);

    List<SixFilters> getFiltersByListIdAndActiveTrueAndDeletedFalse(Long listId);

    @Query(value = "select f.* from six_filters f join regliss_list l on f.list_id = l.id where l.reference = :listReference " +
            "and f.is_active = 1 and f.is_deleted = 0", nativeQuery = true)
    List<SixFilters> getFiltersByListRefAndActiveTrueAndDeletedFalse(@Param("listReference") String listReference);

    boolean existsSixFiltersByFilterTagAndDeletedFalse(String filterTag);
}
