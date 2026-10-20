package com.bnpp.regliss.importer.six.service;

// TODO: re-add project imports (Alt+Enter / Optimize Imports) for the project classes used below
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.CallbackPreferringPlatformTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.Silent.class)
public class SixConfidenceStoreTest {

    @InjectMocks
    private SixConfidenceStore store;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Before
    public void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    }

    @Test
    public void mergeConfidenceOnce_claimed_mergesAndMarks() {
        when(jdbcTemplate.queryForList(startsWith("SELECT ID FROM CTR_BATCH_EXPORT"), eq(Long.class), eq(5L)))
                .thenReturn(Collections.singletonList(5L));
        when(jdbcTemplate.update(startsWith("MERGE"), eq(10L), eq(20L))).thenReturn(42);

        assertEquals(42, store.mergeConfidenceOnce(5L, SixFileKind.STRUCTURED, 20L, 10L, "node1"));
        verify(jdbcTemplate).update("UPDATE CTR_BATCH_EXPORT SET BATCH_NODE_ID = ? WHERE ID = ?", "node1", 5L);
    }

    @Test
    public void mergeConfidenceOnce_notClaimed_minusOne() {
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), eq(5L))).thenReturn(Collections.emptyList());
        assertEquals(-1, store.mergeConfidenceOnce(5L, SixFileKind.OPTIONS, 20L, 10L, "node1"));
        verify(jdbcTemplate, never()).update(startsWith("MERGE"), any(), any());
    }

    @Test
    public void mergeConfidenceOnce_transactionWithoutResult_minusOne() {
        ReflectionTestUtils.setField(store, "transactionManager", mock(CallbackPreferringPlatformTransactionManager.class));
        assertEquals(-1, store.mergeConfidenceOnce(5L, SixFileKind.STRUCTURED, 20L, 10L, "node1"));
        verify(jdbcTemplate, never()).update(startsWith("MERGE"), any(), any());
    }

    @Test
    public void mergeSql_dependentFile() {
        assertEquals("MERGE /*+ LEADING(i) USE_HASH(t) */ INTO SIX_STRUCTURED t USING (SELECT DISTINCT CH_VALOR, CONFIDENCE_LEVEL"
                        + " FROM SIX_INSTRUMENTS        WHERE VERSION_ID = ?) i ON (i.CH_VALOR = t.UNDERLYING_CH AND t.VERSION_ID = ?)"
                        + " WHEN MATCHED THEN UPDATE SET t.CONFIDENCE_LEVEL = i.CONFIDENCE_LEVEL",
                SixConfidenceStore.mergeSql(SixFileKind.STRUCTURED));
    }

    @Test(expected = IllegalArgumentException.class)
    public void mergeSql_instrument_refused() {
        SixConfidenceStore.mergeSql(SixFileKind.INSTRUMENT);
    }

    @Test
    public void takeRow_and_takeOverRow() {
        when(jdbcTemplate.update(anyString(), eq("n"), eq(1L))).thenReturn(1);
        when(jdbcTemplate.update(anyString(), eq("n"), eq(2L))).thenReturn(0);
        assertTrue(store.takeRow(1L, "n"));
        assertFalse(store.takeRow(2L, "n"));
        when(jdbcTemplate.update(anyString(), eq("n"), eq(3L), eq("old"))).thenReturn(1);
        assertTrue(store.takeOverRow(3L, "old", "n"));
        assertFalse(store.takeOverRow(4L, "old", "n"));
    }

    @Test
    public void currentNodeOfRow() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(1L))).thenReturn(Collections.singletonList("n1"));
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(2L))).thenReturn(Collections.emptyList());
        assertEquals("n1", store.currentNodeOfRow(1L));
        assertNull(store.currentNodeOfRow(2L));
    }

    @Test
    public void deleteRow() {
        store.deleteRow(9L);
        verify(jdbcTemplate).update("DELETE FROM CTR_BATCH_EXPORT WHERE ID = ?", 9L);
    }

    private void jobParams(long job, String... params) {
        when(jdbcTemplate.queryForList(startsWith("SELECT JOB_PARAMS"), eq(String.class), eq(job))).thenReturn(Arrays.asList(params));
    }

    @Test
    public void deliveryKeyOfJob_fromJsonFileNames() {
        jobParams(1L, "{\"listId\":1,\"filenames\":[\"README.txt\",\"GSM_1_202_EU_INSTR_FULL_20261004_101500.xml\"]}");
        assertEquals(Long.valueOf(20261004101500L), store.deliveryKeyOfJob(1L));
    }

    @Test
    public void deliveryKeyOfJob_jsonWithoutMatch_fallsBackToText() {
        jobParams(2L, "{\"listId\":1,\"filenames\":[\"README.txt\"],\"note\":\"x_20261004_101500\"}");
        assertEquals(Long.valueOf(20261004101500L), store.deliveryKeyOfJob(2L));
        jobParams(3L, "{\"listId\":1}");
        assertNull(store.deliveryKeyOfJob(3L));
    }

    @Test
    public void deliveryKeyOfJob_notJson_textSearch() {
        jobParams(4L, "listId=1, filenames=GSM_INSTR_20261004_101500.xml");
        assertEquals(Long.valueOf(20261004101500L), store.deliveryKeyOfJob(4L));
        jobParams(5L, "nothing here");
        assertNull(store.deliveryKeyOfJob(5L));
    }

    @Test
    public void deliveryKeyOfJob_noParams() {
        when(jdbcTemplate.queryForList(startsWith("SELECT JOB_PARAMS"), eq(String.class), eq(6L))).thenReturn(Collections.emptyList());
        assertNull(store.deliveryKeyOfJob(6L));
        when(jdbcTemplate.queryForList(startsWith("SELECT JOB_PARAMS"), eq(String.class), eq(7L)))
                .thenReturn(Collections.singletonList(null));
        assertNull(store.deliveryKeyOfJob(7L));
    }
}
