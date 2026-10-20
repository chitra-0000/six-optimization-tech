package com.bnpp.regliss.importer.six.service;

// TODO: re-add project imports (Alt+Enter / Optimize Imports) for the project classes used below

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.CallbackPreferringPlatformTransactionManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SixFilteredStoreTest {

    private static final LocalDateTime NOW = LocalDateTime.now();

    private SixFilteredStore store;
    private JdbcTemplate jdbc;
    private NamedParameterJdbcTemplate named;

    @Before
    public void setUp() {
        store = new SixFilteredStore();
        jdbc = mock(JdbcTemplate.class);
        named = mock(NamedParameterJdbcTemplate.class);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        ReflectionTestUtils.setField(store, "jdbcTemplate", jdbc);
        ReflectionTestUtils.setField(store, "namedJdbcTemplate", named);
        ReflectionTestUtils.setField(store, "transactionManager", tm);
        store.init();
    }

    private void transactionsWithoutResult() {
        ReflectionTestUtils.setField(store, "transactionManager", mock(CallbackPreferringPlatformTransactionManager.class));
        store.init();
    }

    private static ResultSet rs(long id, String type, Long version, Long job, String list, String status, LocalDateTime updated,
                                String reason) {
        try {
            return SixPollerRowFixtures.resultSet(id, type, 1L, version, job, list, status, updated, reason);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Maps the result sets with the RowMapper passed by the store (covers its mapping). */
    @SuppressWarnings("unchecked")
    private static List<Object> mapped(Object mapper, ResultSet... rows) throws SQLException {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            out.add(((RowMapper<Object>) mapper).mapRow(rows[i], i));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private void jdbcRows(String sqlStart, ResultSet... rows) {
        when(jdbc.query(startsWith(sqlStart), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(inv -> mapped(inv.getArgument(1), rows));
        when(jdbc.query(startsWith(sqlStart), any(RowMapper.class), any()))
                .thenAnswer(inv -> mapped(inv.getArgument(1), rows));
        when(jdbc.query(startsWith(sqlStart), any(RowMapper.class), any(), any()))
                .thenAnswer(inv -> mapped(inv.getArgument(1), rows));
    }

    @SuppressWarnings("unchecked")
    private void namedRows(String sqlStart, ResultSet... rows) {
        when(named.query(startsWith(sqlStart), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(inv -> mapped(inv.getArgument(2), rows));
    }

    // ------------------------------------------------------------------ filtered rows

    @Test
    public void deleteVersionOfList_chunkByChunk() {
        when(jdbc.update("DELETE FROM FILTERED_SIX_INSTRUMENTS WHERE VERSION_ID = ? AND SIX_LIST_REF = ? AND ROWNUM <= ?", 5L, "951", 5000))
                .thenReturn(5000, 3);
        assertEquals(5003, store.deleteVersionOfList(SixFileKind.INSTRUMENT, 5L, "951"));
    }

    @Test
    public void deleteVersionOfList_noResult_zero() {
        transactionsWithoutResult();
        assertEquals(0, store.deleteVersionOfList(SixFileKind.OPTIONS, 5L, "951"));
    }

    @Test
    public void removeFilteredRowsOfDroppedRow() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("951"), eq(5L), eq("STRUCT"))).thenReturn(0, 1, null);
        when(jdbc.update(startsWith("DELETE FROM FILTERED_SIX_STRUCTURED"), anyLong(), anyString(), anyInt())).thenReturn(7);
        SixFilteredStore.PollerRow row = SixPollerRowFixtures.row(1, "STRUCT", 5L, 3L, "951", SixFilteredStore.DROPPED, NOW);

        assertEquals(7, store.removeFilteredRowsOfDroppedRow(row));
        assertEquals(-1, store.removeFilteredRowsOfDroppedRow(row));               // another row still uses them
        assertEquals(7, store.removeFilteredRowsOfDroppedRow(row));                 // count not available: removed

        assertEquals(-1, store.removeFilteredRowsOfDroppedRow(SixPollerRowFixtures.row(2, "OTHER", 5L, 3L, "951", "DROPPED", NOW)));
        assertEquals(-1, store.removeFilteredRowsOfDroppedRow(SixPollerRowFixtures.row(3, "STRUCT", null, 3L, "951", "DROPPED", NOW)));
        assertEquals(-1, store.removeFilteredRowsOfDroppedRow(SixPollerRowFixtures.row(4, "STRUCT", 5L, 3L, null, "DROPPED", NOW)));
    }

    // ------------------------------------------------------------------ status changes

    @Test
    public void announceAndDropPending_onlyFromPending() {
        when(jdbc.update(startsWith("UPDATE SIX_FILTERED_POLLER SET STATUS = ?, UPDATED_TIME = ? WHERE ID = ?"), any(), any(), any(), any()))
                .thenReturn(1, 1, 0);
        store.announce(1L);
        store.dropPending(2L);
        try {
            store.announce(3L);
            fail();
        } catch (IllegalStateException e) {
            assertEquals("SIX_FILTERED_POLLER row 3 is not PENDING any more", e.getMessage());
        }
    }

    @Test
    public void dropPendingRowsOfJob() {
        when(jdbc.update(startsWith("UPDATE SIX_FILTERED_POLLER SET STATUS = ?, UPDATED_TIME = ? WHERE BATCH_JOB_EXECUTION_ID"),
                eq(SixFilteredStore.DROPPED), any(), eq(9L), eq(SixFilteredStore.PENDING))).thenReturn(4);
        assertEquals(4, store.dropPendingRowsOfJob(9L));
    }

    @Test
    public void statusChangesOfIds_inChunksOfAThousand() {
        List<Long> ids = LongStream.rangeClosed(1, 1001).boxed().collect(Collectors.toList());
        store.dropReady(ids);
        store.markBuilt(Collections.singletonList(1L));
        store.markBuildFailed(Collections.singletonList(1L));
        store.markReleased(Collections.singletonList(1L));
        store.markReleaseFailed(Collections.singletonList(1L));
        store.touchBuilding(Collections.singletonList(1L));
        store.dropUnfinished(Collections.singletonList(1L));
        store.markBuilt(Collections.emptyList());
        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(named, times(10)).update(startsWith("UPDATE SIX_FILTERED_POLLER SET STATUS = :toStatus"), params.capture());
        List<String> moves = params.getAllValues().stream().map(p -> p.getValue("fromStatus") + ">" + p.getValue("toStatus"))
                .collect(Collectors.toList());
        assertEquals(Arrays.asList("READY>DROPPED", "READY>DROPPED", "BUILDING>BUILT", "BUILDING>DROPPED", "BUILT>DONE",
                "BUILT>DROPPED", "BUILDING>BUILDING", "PENDING>DROPPED", "READY>DROPPED", "BUILDING>DROPPED"), moves);
        assertEquals(1000, ((List<?>) params.getAllValues().get(0).getValue("ids")).size());
        assertEquals(1, ((List<?>) params.getAllValues().get(1).getValue("ids")).size());
    }

    // ------------------------------------------------------------------ reads

    @Test
    public void reads_mapTheRows() {
        jdbcRows("SELECT ID, FILE_TYPE", rs(1, "INSTR", 5L, 3L, "951", "READY", NOW, null));
        namedRows("SELECT ID, FILE_TYPE", rs(2, "STRUCT", null, null, "235", "BUILT", null, "REGENERATION"));

        SixFilteredStore.PollerRow ready = store.readyRows("951").get(0);
        assertEquals(1L, ready.getId());
        assertEquals("INSTR", ready.getFileType());
        assertEquals(Long.valueOf(1L), ready.getRawListId());
        assertEquals(Long.valueOf(5L), ready.getRawVersionId());
        assertEquals(Long.valueOf(3L), ready.getBatchJobExecutionId());
        assertEquals("951", ready.getListRef());
        assertEquals("READY", ready.getStatus());
        assertEquals(NOW.withNano(NOW.getNano()), ready.getUpdatedTime());
        assertEquals(SixExportRunGuard.GENERATION, ready.getGenerationReason());     // NULL read as GENERATION
        assertEquals("list 951 INSTR READY GENERATION (row 1, raw version 5, job 3)", ready.toString());
        assertEquals(1, store.openRowsOfList("951", 5L).size());

        SixFilteredStore.PollerRow built = store.rowsInStatus(SixFilteredStore.BUILT).get(0);
        assertNull(built.getRawVersionId());
        assertNull(built.getUpdatedTime());
        assertEquals("REGENERATION", built.getGenerationReason());
    }

    @Test
    public void jobsStillFiltering_andJobLists() {
        when(jdbc.queryForList(anyString(), eq(Long.class), eq("951"), eq(SixFilteredStore.PENDING), eq("GENERATION")))
                .thenReturn(Arrays.asList(1L, 2L));
        assertEquals(Arrays.asList(1L, 2L), store.jobsStillFiltering("951", "GENERATION"));

        when(jdbc.queryForList(anyString(), eq(String.class), eq(9L))).thenReturn(Arrays.asList("DONE", "BUILT", "DROPPED", "READY", "DONE"));
        SixFilteredStore.JobLists lists = store.jobLists(9L);
        assertEquals(5, lists.getTotal());
        assertEquals(2, lists.getDone());
        assertEquals(1, lists.getBuilt());
        assertEquals(1, lists.getDropped());
        assertEquals(1, lists.getOpen());
        assertFalse(lists.allDelivered());
        when(jdbc.queryForList(anyString(), eq(String.class), eq(8L))).thenReturn(Arrays.asList("DONE", "DONE"));
        assertTrue(store.jobLists(8L).allDelivered());
        when(jdbc.queryForList(anyString(), eq(String.class), eq(7L))).thenReturn(Collections.emptyList());
        assertFalse(store.jobLists(7L).allDelivered());
    }

    // ------------------------------------------------------------------ older rows (part 4)

    @Test
    public void deleteOlderVersionRows_keepsRowsStillInUse() {
        jdbcRows("SELECT ID, FILE_TYPE",
                rs(1, "INSTR", 4L, 7L, "951", SixFilteredStore.DONE, NOW, null),            // finished: removed
                rs(2, "INSTR", 4L, 7L, "951", SixFilteredStore.BUILT, NOW, null),           // held file: kept
                rs(3, "INSTR", 4L, 7L, "951", SixFilteredStore.BUILDING, NOW, null),        // building now: kept
                rs(4, "INSTR", 4L, 8L, "951", SixFilteredStore.BUILDING, NOW.minusHours(2), null),   // job 8 stopped: removed
                rs(5, "INSTR", 4L, 9L, "951", SixFilteredStore.PENDING, NOW, null),          // job 9 works: kept
                rs(6, "INSTR", 4L, 9L, "951", SixFilteredStore.READY, NOW, null),            // same job, asked once
                rs(7, "INSTR", 4L, null, "951", SixFilteredStore.PENDING, NOW, null),        // no job: removed
                rs(8, "INSTR", 4L, 8L, "951", SixFilteredStore.BUILDING, null, null),        // never touched, job stopped
                rs(9, "INSTR", 4L, 7L, "951", "FAILED", NOW, null));                         // signal row: removed
        when(named.update(startsWith("DELETE FROM SIX_FILTERED_POLLER"), any(MapSqlParameterSource.class))).thenReturn(5);
        List<Long> asked = new ArrayList<>();

        int removed = store.deleteOlderVersionRows("INSTR", 5L, job -> {
            asked.add(job);
            return job == 9L;
        });

        assertEquals(5, removed);
        assertEquals(Arrays.asList(8L, 9L), asked);
        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(named).update(startsWith("DELETE FROM SIX_FILTERED_POLLER WHERE ID IN (:ids) AND STATUS <> :built"), params.capture());
        assertEquals(Arrays.asList(1L, 4L, 7L, 8L, 9L), params.getValue().getValue("ids"));
    }

    @Test
    public void deleteOlderVersionRows_nothingOlder() {
        jdbcRows("SELECT ID, FILE_TYPE");
        assertEquals(0, store.deleteOlderVersionRows("STRUCT", 5L, job -> false));
        verify(named, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    // ------------------------------------------------------------------ claim / release

    @Test
    public void claim_perReason_whenEveryFileTypeIsReady() {
        jdbcRows("SELECT ID, FILE_TYPE",
                rs(1, "INSTR", 5L, 3L, "951", "READY", NOW, "REGENERATION"),
                rs(2, "INSTR", 5L, 4L, "951", "READY", NOW, null),
                rs(3, "STRUCT", 6L, 4L, "951", "READY", NOW, null),
                rs(4, null, 6L, null, "951", "READY", NOW, null));

        SixFilteredStore.PollerClaim claim = store.claimPollerRows("951", new LinkedHashSet<>(Arrays.asList("INSTR", "STRUCT")));

        assertTrue(claim.isClaimed());
        assertEquals(Arrays.asList(2L, 3L, 4L), SixFilteredStore.ids(claim.getRows()));
        assertEquals(new LinkedHashSet<>(Collections.singletonList(4L)), claim.jobIds());
        assertEquals("INSTR(v5), STRUCT(v6), null(v6)", claim.toString());
        verify(named).update(startsWith("UPDATE SIX_FILTERED_POLLER"), any(MapSqlParameterSource.class));
    }

    @Test
    public void claim_missingFileType_nothingChanged() {
        jdbcRows("SELECT ID, FILE_TYPE", rs(1, "INSTR", 5L, 3L, "951", "READY", NOW, null));
        SixFilteredStore.PollerClaim claim = store.claimPollerRows("951", new LinkedHashSet<>(Arrays.asList("INSTR", "STRUCT")));
        assertFalse(claim.isClaimed());
        assertEquals(1, claim.getRows().size());
        verify(named, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    public void claim_noResult_notClaimed() {
        transactionsWithoutResult();
        SixFilteredStore.PollerClaim claim = store.claimPollerRows("951", Collections.singleton("INSTR"));
        assertFalse(claim.isClaimed());
        assertTrue(claim.getRows().isEmpty());
    }

    @Test
    public void release_allRowsLocked_orNot() {
        namedRows("SELECT ID, FILE_TYPE", rs(1, "INSTR", 5L, 3L, "951", "BUILT", NOW, null));
        assertEquals("released 1", store.releaseInTx(Collections.singletonList(1L), rows -> "released " + rows.size()));
        assertNull(store.releaseInTx(Arrays.asList(1L, 2L), rows -> "never"));
    }

    @Test
    public void release_sizeChecked() {
        List<Long> none = Collections.emptyList();
        List<Long> tooMany = LongStream.rangeClosed(1, 1001).boxed().collect(Collectors.toList());
        for (List<Long> ids : Arrays.asList(none, tooMany)) {
            try {
                store.releaseInTx(ids, rows -> "x");
                fail();
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage().startsWith("A delivery has 1 to 1000 list rows"));
            }
        }
    }

    @Test
    public void idsAndJobIds() {
        List<SixFilteredStore.PollerRow> rows = Arrays.asList(SixPollerRowFixtures.row(1, "INSTR", 5L, 3L, "951", "READY", NOW),
                SixPollerRowFixtures.row(2, "INSTR", 5L, null, "951", "READY", NOW), SixPollerRowFixtures.row(3, "INSTR", 5L, 3L, "951", "READY", NOW));
        assertEquals(Arrays.asList(1L, 2L, 3L), SixFilteredStore.ids(rows));
        assertEquals(Collections.singleton(3L), SixFilteredStore.jobIdsOf(rows));
    }
}
