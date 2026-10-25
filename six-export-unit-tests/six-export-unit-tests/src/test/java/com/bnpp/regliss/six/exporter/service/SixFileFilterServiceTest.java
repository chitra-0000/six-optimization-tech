package com.bnpp.regliss.six.exporter.service;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// TODO: re-add project imports for: SixFilters, SixSubFilters, InstrumentFile, StructuredFile, OptionsFile, DynamicSqlBuilder,
// SixFileKind

public class SixFileFilterServiceTest {

    private SixFileFilterService service;
    private NamedParameterJdbcTemplate reads;
    private DynamicSqlBuilder sqlBuilder;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() {
        service = new SixFileFilterService();
        NamedParameterJdbcTemplate shared = mock(NamedParameterJdbcTemplate.class);
        when(shared.getJdbcTemplate()).thenReturn(new JdbcTemplate(mock(DataSource.class)));
        ReflectionTestUtils.setField(service, "jdbcTemplate", shared);
        ReflectionTestUtils.setField(service, "filterFetchSize", 0);
        service.initReadTemplate();
        NamedParameterJdbcTemplate created = (NamedParameterJdbcTemplate) ReflectionTestUtils.getField(service, "readTemplate");
        assertEquals(1, created.getJdbcTemplate().getFetchSize());                 // at least one row per round trip

        reads = mock(NamedParameterJdbcTemplate.class);
        ReflectionTestUtils.setField(service, "readTemplate", reads);
        sqlBuilder = mock(DynamicSqlBuilder.class);
        ReflectionTestUtils.setField(service, "sqlBuilder", sqlBuilder);
        when(sqlBuilder.buildWhereClause(any(), any(), anyString(), anyString(), any(), any())).thenReturn("x = 1");
        when(sqlBuilder.buildBaseWhereClause(any(), any(), anyString())).thenReturn("b = 1");
        when(reads.query(anyString(), any(MapSqlParameterSource.class), any(ResultSetExtractor.class))).thenReturn(new ArrayList<>());
    }

    @Test
    public void initReadTemplate_sharedTemplateWithoutDataSource_refused() {
        SixFileFilterService other = new SixFileFilterService();
        NamedParameterJdbcTemplate shared = mock(NamedParameterJdbcTemplate.class);
        when(shared.getJdbcTemplate()).thenReturn(new JdbcTemplate());
        ReflectionTestUtils.setField(other, "jdbcTemplate", shared);
        try {
            other.initReadTemplate();
            fail();
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("no DataSource"));
        }
    }

    private static SixFilters filter(String fileType, boolean cmic, boolean e014071) {
        SixFilters f = mock(SixFilters.class);
        when(f.getFileType()).thenReturn(fileType);
        when(f.isExcludeCMIC()).thenReturn(cmic);
        when(f.isExcludeE014071()).thenReturn(e014071);
        Set<SixSubFilters> subs = new LinkedHashSet<>(Collections.singletonList(mock(SixSubFilters.class)));
        when(f.getActiveSixSubfilters()).thenReturn(subs);
        return f;
    }

    private static InstrumentFile instrument(long id, String isin) {
        InstrumentFile f = new InstrumentFile();
        f.setId(id);
        f.setIsin(isin);
        return f;
    }

    /** The exclusion query returns these rows (id, isin). */
    private void exclusionRows(Object[]... rows) {
        doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(2);
            for (Object[] row : rows) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getString("i_isin")).thenReturn((String) row[1]);
                when(rs.getLong("i_id")).thenReturn((Long) row[0]);
                handler.processRow(rs);
            }
            return null;
        }).when(reads).query(anyString(), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));
    }

    @SuppressWarnings("unchecked")
    private void filteredRows(List<?> rows) {
        when(reads.query(anyString(), any(MapSqlParameterSource.class), any(ResultSetExtractor.class))).thenReturn(new ArrayList<>(rows));
    }

    @SuppressWarnings("unchecked")
    private List<String> readSqls() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(reads, atLeastOnce()).query(sql.capture(), any(MapSqlParameterSource.class), any(ResultSetExtractor.class));
        return sql.getAllValues();
    }

    // ------------------------------------------------------------------ filters

    @Test
    @SuppressWarnings("unchecked")
    public void filterInstrument_allTargetsIsinsAndCmicRowsRemoved() {
        doAnswer(inv -> {
            ((Set<String>) inv.getArgument(4)).add("EMPTY");
            ((Set<String>) inv.getArgument(5)).add("CONTAINS");
            return "a = 1";
        }).when(sqlBuilder).buildWhereClause(any(), any(), anyString(), eq("i.isin"), any(), any());
        filteredRows(Arrays.asList(instrument(1, "KEEP"), instrument(2, "EMPTY"), instrument(3, "CONTAINS"), instrument(4, "CMIC")));
        exclusionRows(new Object[]{4L, "CMIC"}, new Object[]{5L, "EMPTY"}, new Object[]{6L, "CONTAINS"});

        SixFileFilterService.Exclusions exclusions = service.exclusionsFor(SixFileKind.INSTRUMENT, 10L, 20L,
                Collections.singletonList(filter("Instrument File", false, false)), null);
        assertSame(SixFileKind.INSTRUMENT, exclusions.getKind());
        List<SixFilters> filters = Arrays.asList(filter("Instrument File", true, false), filter("Structured File", false, true),
                filter("DATA EXTRACTOR 1", false, false));

        List<InstrumentFile> kept = service.filterInstrument(filters, exclusions);

        assertEquals(Collections.singletonList(1L), kept.stream().map(InstrumentFile::getId).collect(Collectors.toList()));
        String include = readSqls().get(0);
        assertTrue(include, include.startsWith("SELECT " + SixFileKind.INSTRUMENT.joinHint() + " i.ID AS i_id, i.LIST_ID AS i_list_id"));
        assertTrue(include, include.contains(" FROM SIX_INSTRUMENTS i JOIN SIX_TARGET t ON i.ID = t.SIX_INSTRU_ID"
                + " WHERE i.LIST_ID = :listId AND i.VERSION_ID = :versionId AND a = 1 AND a = 1"));
        verify(sqlBuilder, times(3)).buildWhereClause(any(), any(), anyString(), eq("i.isin"), any(), any());   // 2 list filters + 1 CMIC
    }

    @Test
    public void filterStructuredAndOptions_viaKind_exclusionJoinWhenUsed() {
        doAnswer(inv -> {
            ((MapSqlParameterSource) inv.getArgument(1)).addValue("sixExclusionList", "EXCL");
            return "ex.code IS NOT NULL";
        }).when(sqlBuilder).buildWhereClause(any(), any(), anyString(), eq("i.host_isin"), any(), any());
        StructuredFile s = new StructuredFile();
        s.setId(1L);
        s.setHostIsin("H");
        filteredRows(Collections.singletonList(s));
        SixFileFilterService.Exclusions structured = service.exclusionsFor(SixFileKind.STRUCTURED, 1L, 2L, null, null);

        List<Object> rows = service.filter(SixFileKind.STRUCTURED, Collections.singletonList(filter("structured file", false, false)), structured);

        assertEquals(1, rows.size());
        String sql = readSqls().get(0);
        assertTrue(sql, sql.startsWith("SELECT " + SixFileKind.STRUCTURED.joinHintWithExclusion()));
        assertTrue(sql, sql.contains(DynamicSqlBuilder.exclusionJoin(SixFileKind.STRUCTURED) + " WHERE i.LIST_ID"));

        OptionsFile o = new OptionsFile();
        o.setId(1L);
        o.setIsinOption("O");
        filteredRows(Collections.singletonList(o));
        SixFileFilterService.Exclusions options = service.exclusionsFor(SixFileKind.OPTIONS, 1L, 2L, null, null);
        List<Object> optionRows = service.filter(SixFileKind.OPTIONS, Collections.singletonList(filter("Data extractor 2", false, false)), options);
        assertEquals(1, optionRows.size());
        try {
            optionRows.clear();
            fail();
        } catch (UnsupportedOperationException e) {
            assertEquals(1, optionRows.size());                                // read-only view
        }
        filteredRows(Collections.emptyList());
        assertEquals(0, service.filter(SixFileKind.INSTRUMENT, Collections.emptyList(),
                service.exclusionsFor(SixFileKind.INSTRUMENT, 1L, 2L, null, null)).size());
    }

    @Test
    public void filtersWithoutCondition_skipped_nullResult_empty() {
        when(sqlBuilder.buildWhereClause(any(), any(), anyString(), anyString(), any(), any())).thenReturn(" ", null);
        when(reads.query(anyString(), any(MapSqlParameterSource.class), any(ResultSetExtractor.class))).thenReturn(null);
        SixFileFilterService.Exclusions exclusions = service.exclusionsFor(SixFileKind.INSTRUMENT, 1L, 2L, null, null);
        List<SixFilters> filters = Arrays.asList(filter("Instrument File", false, false), filter("Instrument File", false, false));

        assertTrue(service.filterInstrument(filters, exclusions).isEmpty());

        assertTrue(readSqls().get(0).endsWith("WHERE i.LIST_ID = :listId AND i.VERSION_ID = :versionId"));
    }

    @Test
    public void exclusionsOfAnotherFileType_refused() {
        SixFileFilterService.Exclusions options = service.exclusionsFor(SixFileKind.OPTIONS, 1L, 2L, null, null);
        List<SixFilters> none = Collections.emptyList();
        try {
            service.filterInstrument(none, options);
            fail();
        } catch (IllegalArgumentException e) {
            assertEquals("Exclusions of OPTIONS used for INSTRUMENT", e.getMessage());
        }
        Collection<String> isins = Collections.singleton("A");
        try {
            service.structuredContentRows(none, options, isins);
            fail();
        } catch (IllegalArgumentException e) {
            assertEquals("Exclusions of OPTIONS used for STRUCTURED", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ exclusions

    @Test
    public void prepareExclusions_onlyWhatTheListsAsk_computedOnce() {
        exclusionRows(new Object[]{7L, "X"});
        List<SixFilters> cmic = Collections.singletonList(filter("Instrument File", false, false));
        List<SixFilters> e014071 = Collections.singletonList(filter("Instrument File", false, false));
        List<List<SixFilters>> lists = Arrays.asList(
                Collections.singletonList(filter("Instrument File", true, false)),
                Arrays.asList(filter("Options File", true, true), filter("Data extractor 2", false, true)));

        SixFileFilterService.Exclusions exclusions = service.prepareExclusions(SixFileKind.INSTRUMENT, 1L, 2L, cmic, e014071, lists);

        verify(reads, times(2)).query(anyString(), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));
        assertEquals(Collections.singleton(7L), exclusions.idsToRemove(true, true));                 // no new query
        assertTrue(exclusions.idsToRemove(false, false).isEmpty());
        verify(reads, times(2)).query(anyString(), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(reads, times(2)).query(sql.capture(), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));
        assertTrue(sql.getValue(), sql.getValue().startsWith("SELECT " + SixFileKind.INSTRUMENT.joinHint() + " i.ID AS i_id, i.isin AS i_isin FROM"));
        assertTrue(sql.getValue(), sql.getValue().endsWith(" AND x = 1"));
    }

    @Test
    public void prepareExclusions_nothingAsked_nothingRead_andNoConditionMeansNoExclusion() {
        SixFileFilterService.Exclusions exclusions = service.prepareExclusions(SixFileKind.INSTRUMENT, 1L, 2L,
                Collections.singletonList(filter("Options File", false, false)), Collections.emptyList(),
                Collections.singletonList(Collections.singletonList(filter("Instrument File", false, false))));
        verify(reads, never()).query(anyString(), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));
        assertTrue(exclusions.idsToRemove(true, true).isEmpty());                  // CMIC filter of another type: no condition
        verify(reads, never()).query(anyString(), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));
    }

    // ------------------------------------------------------------------ same-ISIN content rows

    @Test
    @SuppressWarnings("unchecked")
    public void contentRows_isinsBoundByThousand_exclusionsApplied() {
        List<String> isins = IntStream.range(0, 1001).mapToObj(i -> "I" + i).collect(Collectors.toList());
        isins.add("I0");                                                         // duplicate: read once
        when(reads.query(anyString(), any(MapSqlParameterSource.class), any(ResultSetExtractor.class)))
                .thenReturn(new ArrayList<>(Arrays.asList(instrument(1, "I1"), instrument(4, "I4"))), (List<Object>) null);
        exclusionRows(new Object[]{4L, "I4"});
        SixFileFilterService.Exclusions exclusions = service.exclusionsFor(SixFileKind.INSTRUMENT, 1L, 2L,
                Collections.singletonList(filter("Instrument File", false, false)), null);

        List<InstrumentFile> rows = service.instrumentContentRows(Arrays.asList(filter("Instrument File", true, false),
                filter("Options File", false, false)), exclusions, isins);

        assertEquals(Collections.singletonList(1L), rows.stream().map(InstrumentFile::getId).collect(Collectors.toList()));
        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(reads, times(2)).query(sql.capture(), params.capture(), any(ResultSetExtractor.class));
        assertTrue(sql.getValue(), sql.getValue().endsWith(" AND b = 1 AND i.isin IN (:mergeIsins) ORDER BY i.ID, t.ID"));
    }

    @Test
    public void contentRows_noIsin_orNoCondition() {
        SixFileFilterService.Exclusions structured = service.exclusionsFor(SixFileKind.STRUCTURED, 1L, 2L, null, null);
        List<SixFilters> filters = Collections.singletonList(filter("Structured File", false, true));
        assertTrue(service.structuredContentRows(filters, structured, Collections.emptyList()).isEmpty());
        verify(reads, never()).query(anyString(), any(MapSqlParameterSource.class), any(ResultSetExtractor.class));

        when(sqlBuilder.buildBaseWhereClause(any(), any(), anyString())).thenReturn("");
        assertTrue(service.structuredContentRows(filters, structured, Collections.singleton("H")).isEmpty());
        assertTrue(readSqls().get(0).contains("WHERE i.LIST_ID = :listId AND i.VERSION_ID = :versionId AND i.host_isin IN (:mergeIsins)"));
    }
}
