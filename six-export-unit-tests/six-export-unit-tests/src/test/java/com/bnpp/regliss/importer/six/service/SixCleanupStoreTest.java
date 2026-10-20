package com.bnpp.regliss.importer.six.service;

// TODO: re-add project imports (Alt+Enter / Optimize Imports) for the project classes used below

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.CallbackPreferringPlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.Silent.class)
public class SixCleanupStoreTest {

    @InjectMocks
    private SixCleanupStore store;

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private PlatformTransactionManager transactionManager;

    @Before
    public void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        ReflectionTestUtils.setField(store, "chunkSize", 2);
        store.init();
    }

    /** A transaction manager whose transactions return no result (covers the "null result" guards). */
    private void transactionsWithoutResult() {
        ReflectionTestUtils.setField(store, "transactionManager", mock(CallbackPreferringPlatformTransactionManager.class));
        store.init();
    }

    private static BadSqlGrammarException refused() {
        return new BadSqlGrammarException("truncate", "TRUNCATE", new SQLException("ORA-01031: insufficient privileges"));
    }

    @Test
    public void rawTables() {
        assertEquals("SIX_INSTRUMENTS", SixCleanupStore.RawTable.INSTRUMENTS.getTable());
        assertEquals("INSTR", SixCleanupStore.RawTable.INSTRUMENTS.getPollerFileType());
        assertEquals("SIX_STRUCTURED", SixCleanupStore.RawTable.STRUCTURED.getTable());
        assertEquals("STRUCT", SixCleanupStore.RawTable.STRUCTURED.getPollerFileType());
        assertEquals("SIX_OPTION", SixCleanupStore.RawTable.OPTIONS.getTable());
        assertEquals("OPTIONS", SixCleanupStore.RawTable.OPTIONS.getPollerFileType());
        assertSame(SixCleanupStore.ClearResult.BUSY, SixCleanupStore.ClearResult.valueOf("BUSY"));
    }

    @Test
    public void clear_lockThenTruncateEveryTable() {
        assertSame(SixCleanupStore.ClearResult.CLEARED, store.clearFilteredTables(() -> true));
        InOrder order = inOrder(jdbcTemplate);
        order.verify(jdbcTemplate).execute("LOCK TABLE SIX_FILTERED_POLLER IN EXCLUSIVE MODE NOWAIT");
        order.verify(jdbcTemplate).execute("TRUNCATE TABLE FILTERED_SIX_TARGET");
        order.verify(jdbcTemplate).execute("TRUNCATE TABLE FILTERED_SIX_INSTRUMENTS CASCADE");
        order.verify(jdbcTemplate).execute("TRUNCATE TABLE FILTERED_SIX_STRUCTURED CASCADE");
        order.verify(jdbcTemplate).execute("TRUNCATE TABLE FILTERED_SIX_OPTION CASCADE");
    }

    @Test
    public void clear_exportStartedMeanwhile_busy() {
        assertSame(SixCleanupStore.ClearResult.BUSY, store.clearFilteredTables(() -> false));
        verify(jdbcTemplate, never()).execute(eq("TRUNCATE TABLE FILTERED_SIX_TARGET"));
    }

    @Test
    public void clear_noResult_busy() {
        transactionsWithoutResult();
        assertSame(SixCleanupStore.ClearResult.BUSY, store.clearFilteredTables(() -> true));
    }

    @Test
    public void clear_truncateRefused_chunkedDeleteInstead() {
        doThrow(refused()).when(jdbcTemplate).execute("TRUNCATE TABLE FILTERED_SIX_TARGET");
        when(jdbcTemplate.update("DELETE FROM FILTERED_SIX_TARGET WHERE ID IS NOT NULL AND ROWNUM <= ?", 2)).thenReturn(2, 2, 1);

        assertSame(SixCleanupStore.ClearResult.CLEARED, store.clearFilteredTables(() -> true));

        verify(jdbcTemplate, times(3)).update("DELETE FROM FILTERED_SIX_TARGET WHERE ID IS NOT NULL AND ROWNUM <= ?", 2);
        verify(jdbcTemplate).execute("TRUNCATE TABLE FILTERED_SIX_OPTION CASCADE");
    }

    @Test
    public void clear_connectionLost_rethrown() {
        DataAccessResourceFailureException lost = new DataAccessResourceFailureException("connection reset");
        doThrow(lost).when(jdbcTemplate).execute("TRUNCATE TABLE FILTERED_SIX_TARGET");
        try {
            store.clearFilteredTables(() -> true);
            fail();
        } catch (DataAccessResourceFailureException e) {
            assertSame(lost, e);
        }
        verify(jdbcTemplate, never()).update(anyString(), anyInt());
    }

    /** The chunked DELETE transaction returns no row count: treated as 0, the loop stops. */
    @Test
    @SuppressWarnings("unchecked")
    public void chunkedDelete_noResult_stops() {
        CallbackPreferringPlatformTransactionManager tm = mock(CallbackPreferringPlatformTransactionManager.class);
        when(tm.execute(any(), any(TransactionCallback.class))).thenAnswer(inv -> {
            Object result = ((TransactionCallback<Object>) inv.getArgument(1)).doInTransaction(mock(TransactionStatus.class));
            return result instanceof Integer ? null : result;
        });
        ReflectionTestUtils.setField(store, "transactionManager", tm);
        store.init();
        doThrow(refused()).when(jdbcTemplate).execute("TRUNCATE TABLE FILTERED_SIX_TARGET");

        assertSame(SixCleanupStore.ClearResult.CLEARED, store.clearFilteredTables(() -> true));

        verify(jdbcTemplate, times(1)).update("DELETE FROM FILTERED_SIX_TARGET WHERE ID IS NOT NULL AND ROWNUM <= ?", 2);
    }

    @Test
    public void hasRowsOfVersion() {
        when(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SIX_STRUCTURED WHERE VERSION_ID = ? AND ROWNUM <= ?",
                Integer.class, 5L, 1)).thenReturn(1);
        assertTrue(store.hasRowsOfVersion(SixCleanupStore.RawTable.STRUCTURED, 5L));
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(6L), eq(1))).thenReturn(0);
        assertFalse(store.hasRowsOfVersion(SixCleanupStore.RawTable.STRUCTURED, 6L));
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(7L), eq(1))).thenReturn(null);
        assertFalse(store.hasRowsOfVersion(SixCleanupStore.RawTable.STRUCTURED, 7L));
    }

    @Test
    public void deleteOlderVersions_chunkByChunk() {
        when(jdbcTemplate.update("DELETE FROM SIX_INSTRUMENTS WHERE VERSION_ID < ? AND ROWNUM <= ?", 10L, 2)).thenReturn(2, 1);
        assertEquals(3, store.deleteOlderVersions(SixCleanupStore.RawTable.INSTRUMENTS, 10L, () -> true));
    }

    @Test
    public void deleteOlderVersions_stopsWhenAJobStarts() {
        when(jdbcTemplate.update(anyString(), anyLong(), anyInt())).thenReturn(2);
        AtomicInteger checks = new AtomicInteger();
        assertEquals(4, store.deleteOlderVersions(SixCleanupStore.RawTable.OPTIONS, 10L, () -> checks.incrementAndGet() <= 2));
    }

    @Test
    public void deleteOlderVersions_noResult_zero_andChunkAtLeastOne() {
        ReflectionTestUtils.setField(store, "chunkSize", 0);
        transactionsWithoutResult();
        assertEquals(0, store.deleteOlderVersions(SixCleanupStore.RawTable.OPTIONS, 10L, () -> true));
    }
}
