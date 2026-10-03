package com.bnpp.regliss.importer.six.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The few SQL statements the SIX delivery logic needs, all on EXISTING tables
 * (CTR_BATCH_EXPORT, CTR_BATCH_JOB_EXECUTION and the SIX tables) - no new table, no new column.
 *
 * How "exactly once" works without a new table:
 *  - Each imported SIX file already gets one SIX_CONFIDENCE_VALUES row in CTR_BATCH_EXPORT.
 *  - BATCH_NODE_ID of that row is NULL while its confidence merge is still to do.
 *  - A server takes the row with SELECT ... FOR UPDATE SKIP LOCKED (a row another server has
 *    taken is skipped, never waited for), runs the MERGE and sets BATCH_NODE_ID, all in ONE
 *    transaction. Either everything is committed or nothing is - if a server dies in the middle,
 *    Oracle rolls back, the lock disappears and the other server does the job at its next tick.
 */
@Slf4j
@Repository
public class SixConfidenceStore {

    private static final String CLAIM_UNMERGED_ROW =
            "SELECT ID FROM CTR_BATCH_EXPORT WHERE ID = ? AND BATCH_NODE_ID IS NULL FOR UPDATE SKIP LOCKED";
    private static final String MARK_ROW = "UPDATE CTR_BATCH_EXPORT SET BATCH_NODE_ID = ? WHERE ID = ?";
    private static final String TAKE_FREE_ROW =
            "UPDATE CTR_BATCH_EXPORT SET BATCH_NODE_ID = ? WHERE ID = ? AND BATCH_NODE_ID IS NULL";
    private static final String TAKE_OVER_ROW =
            "UPDATE CTR_BATCH_EXPORT SET BATCH_NODE_ID = ? WHERE ID = ? AND BATCH_NODE_ID = ?";
    private static final String DELETE_ROW = "DELETE FROM CTR_BATCH_EXPORT WHERE ID = ?";
    private static final String JOB_PARAMS = "SELECT JOB_PARAMS FROM CTR_BATCH_JOB_EXECUTION WHERE ID = ?";

    /** Same pattern as AutomaticFeedAggregator.SIX_RAW_PATTERN: ..._<date>_<time> */
    private static final Pattern DATE_TIME_IN_TEXT = Pattern.compile("_(\\d{8,})_(\\d{6,})");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Confidence merge of ONE file, exactly once across all servers.
     *
     * @return number of rows updated, or -1 if the row was already merged or is being merged by another server.
     */
    public int mergeConfidenceOnce(long exportRowId, SixFileKind target, long targetVersionId,
                                   long instrumentVersionId, String nodeId) {
        Integer result = newTransaction().execute(status -> {
            List<Long> claimed = jdbcTemplate.queryForList(CLAIM_UNMERGED_ROW, Long.class, exportRowId);
            if (claimed.isEmpty()) {
                return -1;
            }
            int updated = jdbcTemplate.update(mergeSql(target), instrumentVersionId, targetVersionId);
            jdbcTemplate.update(MARK_ROW, nodeId, exportRowId);   // committed together with the MERGE
            return updated;
        });
        return result == null ? -1 : result;
    }

    /** Same statement as the old StructureFileRepository / OptionsFileRepository MERGE, built from SixFileKind. */
    static String mergeSql(SixFileKind target) {
        if (!target.receivesConfidence()) {
            throw new IllegalArgumentException(target + " does not receive a confidence level");
        }
        return "MERGE INTO " + target.getTable() + " t"
                + " USING (SELECT DISTINCT CH_VALOR, CONFIDENCE_LEVEL FROM " + SixFileKind.source().getTable()
                + "        WHERE VERSION_ID = ?) i"
                + " ON (i.CH_VALOR = t." + target.getInstrumentLinkColumn() + " AND t.VERSION_ID = ?)"
                + " WHEN MATCHED THEN UPDATE SET t.CONFIDENCE_LEVEL = i.CONFIDENCE_LEVEL";
    }

    /** Takes a free row (BATCH_NODE_ID NULL) for this server. Atomic: only one server can win. */
    public boolean takeRow(long exportRowId, String nodeId) {
        return jdbcTemplate.update(TAKE_FREE_ROW, nodeId, exportRowId) == 1;
    }

    /** Takes over a row held by a server that is no longer alive (or by this server before a restart). */
    public boolean takeOverRow(long exportRowId, String previousNodeId, String nodeId) {
        return jdbcTemplate.update(TAKE_OVER_ROW, nodeId, exportRowId, previousNodeId) == 1;
    }

    public void deleteRow(long exportRowId) {
        jdbcTemplate.update(DELETE_ROW, exportRowId);
    }

    /**
     * Delivery key (the date+time of the file names) of a SIX import job, read from the existing
     * JOB_PARAMS column (BatchImportAutoLoggedParameters: listId + filenames).
     */
    public Long deliveryKeyOfJob(long batchJobExecutionId) {
        List<String> params = jdbcTemplate.queryForList(JOB_PARAMS, String.class, batchJobExecutionId);
        if (params.isEmpty() || params.get(0) == null) {
            return null;
        }
        String text = params.get(0);
        try {
            JsonNode fileNames = objectMapper.readTree(text).get("filenames");
            if (fileNames != null) {
                for (JsonNode fileName : fileNames) {
                    Long key = SixDeliveryService.deliveryKey(fileName.asText());
                    if (key != null) {
                        return key;
                    }
                }
            }
        } catch (Exception notJson) {
            log.debug("JOB_PARAMS of job {} is not plain JSON, falling back to a text search", batchJobExecutionId);
        }
        Matcher m = DATE_TIME_IN_TEXT.matcher(text);
        return m.find() ? Long.parseLong(m.group(1) + m.group(2)) : null;
    }

    private TransactionTemplate newTransaction() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }
}
