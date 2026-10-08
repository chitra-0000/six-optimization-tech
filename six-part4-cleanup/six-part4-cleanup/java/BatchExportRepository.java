package com.bnpp.regliss.repository.batch;

import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface BatchExportRepository extends EntityRepository<BatchExport, Long>{

    @Query(nativeQuery = true, value = "SELECT e.*, CASE" +
                "    WHEN r.import_file_type = 'DOW_JONES_CORE' THEN 1 " +
                "    WHEN r.import_file_type = 'NOT_SUPPORTED' or r.import_file_type = 'MANUAL_PROCESSING' THEN 3 " +
                "    ELSE 2 " +
                "END  " +
                "AS format " +
                "FROM  ctr_batch_export e " +
                "LEFT OUTER JOIN version v ON v.id = e.version_id AND e.batch_node_id is NULL " +
                "LEFT OUTER JOIN regliss_list r ON r.id = v.list_id " +
                "ORDER BY format, e.request_time asc " +
                "FETCH FIRST 1 ROWS ONLY")
    BatchExport getOrderedBatchExportRecords();

    @Query(nativeQuery = true, value = "select b.* from ctr_batch_export b where b.batch_type = 'SIX_CONFIDENCE_VALUES'")
    List<BatchExport> getByBatchType();

    // SIX part 4 cleanup: a SIX delivery still waiting for its confidence step
    @Query(nativeQuery = true, value = "select count(*) from ctr_batch_export b where b.batch_type = 'SIX_CONFIDENCE_VALUES'")
    long countSixConfidenceEntries();

    // SIX part 4 cleanup: a SIX export (automatic or regeneration) requested but not yet taken by a server
    @Query(nativeQuery = true, value = "select count(*) from ctr_batch_export b where b.batch_type = 'SIX_FILTERED_FILE_GENERATION' and b.batch_node_id is null")
    long countSixExportsNotStarted();
}
