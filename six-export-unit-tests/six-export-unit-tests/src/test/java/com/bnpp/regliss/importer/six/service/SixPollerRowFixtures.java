package com.bnpp.regliss.importer.six.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test helper: builds SixFilteredStore.PollerRow / PollerClaim (final classes, package-private constructors reading a
 * ResultSet) for tests of other packages.
 */
public final class SixPollerRowFixtures {

    private SixPollerRowFixtures() {
    }

    public static SixFilteredStore.PollerRow row(long id, String fileType, Long rawListId, Long rawVersionId, Long jobId,
                                                 String listRef, String status, LocalDateTime updated, String reason) {
        try {
            return new SixFilteredStore.PollerRow(resultSet(id, fileType, rawListId, rawVersionId, jobId, listRef, status, updated, reason));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A ResultSet positioned on one SIX_FILTERED_POLLER row (getLong / wasNull follow the column read last). */
    public static ResultSet resultSet(long id, String fileType, Long rawListId, Long rawVersionId, Long jobId, String listRef,
                                      String status, LocalDateTime updated, String reason) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        Map<String, Long> longs = new HashMap<>();
        longs.put("ID", id);
        longs.put("RAW_LIST_ID", rawListId);
        longs.put("RAW_VERSION_ID", rawVersionId);
        longs.put("BATCH_JOB_EXECUTION_ID", jobId);
        AtomicReference<String> lastRead = new AtomicReference<>();
        when(rs.getLong(anyString())).thenAnswer(inv -> {
            lastRead.set(inv.getArgument(0));
            Long value = longs.get(lastRead.get());
            return value == null ? 0L : value;
        });
        when(rs.wasNull()).thenAnswer(inv -> longs.get(lastRead.get()) == null);
        when(rs.getString("FILE_TYPE")).thenReturn(fileType);
        when(rs.getString("SIX_LIST_REFERENCE")).thenReturn(listRef);
        when(rs.getString("STATUS")).thenReturn(status);
        when(rs.getTimestamp("UPDATED_TIME")).thenReturn(updated == null ? null : Timestamp.valueOf(updated));
        when(rs.getString("GENERATION_REASON")).thenReturn(reason);
        return rs;
    }

    /** A list row of a file type with the usual defaults (GENERATION, raw list 1). */
    public static SixFilteredStore.PollerRow row(long id, String fileType, Long rawVersionId, Long jobId, String listRef,
                                                 String status, LocalDateTime updated) {
        return row(id, fileType, 1L, rawVersionId, jobId, listRef, status, updated, null);
    }

    public static SixFilteredStore.PollerClaim claim(boolean claimed, SixFilteredStore.PollerRow... rows) {
        return new SixFilteredStore.PollerClaim(claimed, Arrays.asList(rows));
    }

    public static SixFilteredStore.PollerClaim claim(boolean claimed, List<SixFilteredStore.PollerRow> rows) {
        return new SixFilteredStore.PollerClaim(claimed, rows);
    }

    /** SixFilteredStore.JobLists of a job whose list rows have these statuses. */
    public static SixFilteredStore.JobLists jobLists(String... statuses) {
        return new SixFilteredStore.JobLists(Arrays.asList(statuses));
    }
}
