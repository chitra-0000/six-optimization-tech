package com.bnpp.regliss.importer.six.extractor;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import javax.persistence.Column;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// TODO: re-add project imports for: SixSubFilters, SixSubORFilters, SixFileKind, ReglissException

public class DynamicSqlBuilderTest {

    private static final String INSTR = "i.isin";
    private static final String STRUCT = "i.host_isin";
    private static final String OPT = "i.isin_option";
    private static final String BASE = "SELECT i.isin FROM SIX_INSTRUMENTS i WHERE i.VERSION_ID = 1";

    private DynamicSqlBuilder builder;
    private NamedParameterJdbcTemplate jdbc;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() {
        builder = new DynamicSqlBuilder();
        jdbc = mock(NamedParameterJdbcTemplate.class);
        ReflectionTestUtils.setField(builder, "jdbcTemplate", jdbc);
        ReflectionTestUtils.setField(builder, "sixExclusionList", "EXCL");
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(3L);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class))).thenAnswer(inv -> {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString(anyString())).thenReturn("XS1");
            return Collections.singletonList(((RowMapper<String>) inv.getArgument(2)).mapRow(rs, 0));
        });
    }

    static SixSubORFilters or(String field, String operator, String value) {
        SixSubORFilters or = mock(SixSubORFilters.class);
        when(or.getFieldName()).thenReturn(field);
        when(or.getOperator()).thenReturn(operator);
        when(or.getFilterValue()).thenReturn(value);
        return or;
    }

    static SixSubFilters sub(SixSubORFilters... ors) {
        SixSubFilters sub = mock(SixSubFilters.class);
        when(sub.getActiveSixSubORfilters()).thenReturn(new LinkedHashSet<>(Arrays.asList(ors)));
        return sub;
    }

    private static Set<SixSubFilters> subs(SixSubFilters... subs) {
        return new LinkedHashSet<>(Arrays.asList(subs));
    }

    /** Parameter names end with a random UUID: replaced by "#" to compare the SQL text. */
    private static String normalized(String sql) {
        return sql.replaceAll("_[0-9a-f]{32}", "#");
    }

    private String where(String rootIsin, SixSubORFilters... ors) {
        return normalized(builder.buildWhereClause(subs(sub(ors)), new MapSqlParameterSource(), BASE, rootIsin,
                new HashSet<>(), new HashSet<>()));
    }

    private String where(MapSqlParameterSource params, SixSubORFilters... ors) {
        return normalized(builder.buildWhereClause(subs(sub(ors)), params, BASE, INSTR, new HashSet<>(), new HashSet<>()));
    }

    private void invalid(String rootIsin, SixSubORFilters or, String message) {
        Set<SixSubFilters> filters = subs(sub(or));
        MapSqlParameterSource params = new MapSqlParameterSource();
        Set<String> empty = new HashSet<>();
        Set<String> contains = new HashSet<>();
        try {
            builder.buildWhereClause(filters, params, BASE, rootIsin, empty, contains);
            fail();
        } catch (ReglissException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(message));
        }
    }

    // ------------------------------------------------------------------ plain fields

    @Test
    public void noSubFilter_noCondition() {
        assertEquals("", builder.buildWhereClause(null, new MapSqlParameterSource(), BASE, INSTR, null, null));
        assertEquals("", builder.buildWhereClause(Collections.emptySet(), new MapSqlParameterSource(), BASE, INSTR, null, null));
        assertEquals("", builder.buildBaseWhereClause(null, new MapSqlParameterSource(), INSTR));
        assertEquals("", builder.buildBaseWhereClause(Collections.emptySet(), new MapSqlParameterSource(), INSTR));
    }

    @Test
    public void emptinessOperators() {
        assertEquals("TRIM(i.active_flag) IS NULL", where(INSTR, or("ACTIVE_FLAG", "is empty", "true")));
        assertEquals("TRIM(i.active_flag) IS NOT NULL", where(INSTR, or("ACTIVE_FLAG", "is empty", "false")));
        assertEquals("TRIM(i.active_flag) IS NOT NULL", where(INSTR, or("ACTIVE_FLAG", "Is Not Empty ", "TRUE")));
        assertEquals("TRIM(i.active_flag) IS NULL", where(INSTR, or("ACTIVE_FLAG", "is not empty", null)));
    }

    @Test
    public void textOperators_alwaysLowerColumn() {
        MapSqlParameterSource params = new MapSqlParameterSource();
        assertEquals("LOWER(i.instr_name) LIKE :INSTR_NAME#", where(params, or("INSTR_NAME", "contains", "Bond")));
        assertTrue(params.getValues().containsValue("%bond%"));
        assertEquals("LOWER(i.instr_name) NOT LIKE :INSTR_NAME#", where(INSTR, or("INSTR_NAME", "not contains", "833")));
        params = new MapSqlParameterSource();
        assertEquals("LOWER(i.instr_name) LIKE :INSTR_NAME#", where(params, or("INSTR_NAME", "starts with", null)));
        assertTrue(params.getValues().containsValue("%"));
        assertEquals("LOWER(i.instr_name) NOT LIKE :INSTR_NAME#", where(INSTR, or("INSTR_NAME", "not starts with", "x")));
    }

    @Test
    public void comparisonOperators_numericOrText() {
        MapSqlParameterSource params = new MapSqlParameterSource();
        assertEquals("TO_NUMBER(i.confidence_level DEFAULT NULL ON CONVERSION ERROR) > :CONFIDENCE_LEVEL#",
                where(params, or("CONFIDENCE_LEVEL", "greater than", "25.5")));
        assertEquals(new BigDecimal("25.5"), params.getValues().values().iterator().next());
        params = new MapSqlParameterSource();
        assertEquals("LOWER(i.active_flag) = :ACTIVE_FLAG#", where(params, or("ACTIVE_FLAG", "equals", "Y")));
        assertEquals("y", params.getValues().values().iterator().next());
        params = new MapSqlParameterSource();
        assertEquals("LOWER(i.active_flag) <> :ACTIVE_FLAG#", where(params, or("ACTIVE_FLAG", "not equals", null)));
        assertEquals("", params.getValues().values().iterator().next());
        assertEquals("TO_NUMBER(i.confidence_level DEFAULT NULL ON CONVERSION ERROR) < :CONFIDENCE_LEVEL#",
                where(INSTR, or("CONFIDENCE_LEVEL", "less than", "-1")));
        assertEquals("TO_NUMBER(i.confidence_level DEFAULT NULL ON CONVERSION ERROR) <= :CONFIDENCE_LEVEL#",
                where(INSTR, or("CONFIDENCE_LEVEL", "less or equal", "+3")));
        assertEquals("TO_NUMBER(i.confidence_level DEFAULT NULL ON CONVERSION ERROR) >= :CONFIDENCE_LEVEL#",
                where(INSTR, or("CONFIDENCE_LEVEL", "greater or equal", "3")));
    }

    @Test
    public void columns_perFileType_andTargetColumns() {
        assertEquals("TRIM(i.isin) IS NULL", where(INSTR, or("isin", "is empty", "true")));
        assertEquals("TRIM(i.host_isin) IS NULL", where(STRUCT, or("ISIN", "is empty", "true")));
        assertEquals("TRIM(i.isin_option) IS NULL", where(OPT, or("ISIN", "is empty", "true")));
        assertEquals("TRIM(i.host_ch) IS NULL", where(STRUCT, or("HOST_CH", "is empty", "true")));
        assertEquals("TRIM(t.sanctioned) IS NULL", where(INSTR, or("SANCTIONED", "is empty", "true")));
        assertEquals("TRIM(t.status) IS NULL", where(INSTR, or("STATUS", "is empty", "true")));     // column not in the map
    }

    @Test
    public void invalidFieldOperatorOrRootIsin_rejected() {
        invalid(INSTR, or("HOST_CH", "is empty", "true"), "is not a column of SIX_INSTRUMENTS or SIX_TARGET");
        invalid(INSTR, or("ISIN; DROP TABLE X", "is empty", "true"), "is not a column");
        invalid(INSTR, or("ACTIVE_FLAG", "between", "1"), "invalid operator 'between'");
        invalid(INSTR, or("ACTIVE_FLAG", null, "1"), "invalid operator ''");
        invalid("i.other", or("ACTIVE_FLAG", "is empty", "true"), "Invalid root isin: i.other");
    }

    @Test
    public void orGroups_andSubFilters() {
        Set<SixSubFilters> filters = subs(
                sub(or("ACTIVE_FLAG", "is empty", "true"), or("SANCTIONED", "equals", "Yes")),
                sub(or("TARGET", "contains", "russia")));
        String sql = normalized(builder.buildWhereClause(filters, new MapSqlParameterSource(), BASE, INSTR, new HashSet<>(), new HashSet<>()));
        assertEquals("(TRIM(i.active_flag) IS NULL OR LOWER(t.sanctioned) = :SANCTIONED#) AND LOWER(t.target) LIKE :TARGET#", sql);
    }

    // ------------------------------------------------------------------ exclusion list

    @Test
    public void exclusionList_withIsins_readOncePerQuery() {
        MapSqlParameterSource params = new MapSqlParameterSource();
        Set<SixSubFilters> filters = subs(
                sub(or("ISIN", "included in exclusion", "true"), or("ISIN", "included in exclusion", "false")),
                sub(or("ISIN", "not included in exclusion", "true"), or("ISIN", "not included in exclusion", "false")));
        String sql = builder.buildWhereClause(filters, params, BASE, INSTR, new HashSet<>(), new HashSet<>());
        assertEquals("(ex.code IS NOT NULL OR (i.isin IS NOT NULL AND ex.code IS NULL))"
                + " AND ((i.isin IS NOT NULL AND ex.code IS NULL) OR ex.code IS NOT NULL)", sql);
        verify(jdbc, times(1)).queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class));
        assertTrue(DynamicSqlBuilder.usesExclusionJoin(params));
        assertEquals("EXCL", params.getValue("sixExclusionList"));
    }

    @Test
    public void exclusionList_withoutIsins_constantConditions() {
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(0L);
        MapSqlParameterSource params = new MapSqlParameterSource();
        assertEquals("(1 = 0 OR 1 = 1)", where(params, or("ISIN", "included in exclusion", "true"),
                or("ISIN", "not included in exclusion", "true")));
        assertFalse(DynamicSqlBuilder.usesExclusionJoin(params));

        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(null);
        assertEquals("1 = 1", where(new MapSqlParameterSource(), or("ISIN", "included in exclusion", "false")));
    }

    @Test
    public void exclusionJoin_onTheIsinColumnOfTheFileType() {
        String join = DynamicSqlBuilder.exclusionJoin(SixFileKind.STRUCTURED);
        assertTrue(join, join.startsWith(" LEFT JOIN (SELECT DISTINCT e.code FROM (SELECT ci.code AS code FROM code_isin ci"));
        assertTrue(join, join.endsWith(") ex ON ex.code = i.host_isin"));
    }

    // ------------------------------------------------------------------ regime rows

    @Test
    public void regimeRows_everyOperator() {
        assertEquals("(LOWER(t.regime) = :REGIME# AND TRIM(t.legal_basis) IS NULL)", where(INSTR, or("EU - REGIME", "is empty", "true")));
        assertEquals("(LOWER(t.regime) = :REGIME# AND TRIM(t.legal_basis) IS NOT NULL)", where(INSTR, or("EU - REGIME", "is empty", "false")));
        assertEquals("(LOWER(t.regime) = :REGIME# AND TRIM(t.legal_basis) IS NOT NULL)", where(INSTR, or("EU - REGIME", "is not empty", "true")));
        assertEquals("(LOWER(t.regime) = :REGIME# AND TRIM(t.legal_basis) IS NULL)", where(INSTR, or("EU - REGIME", "is not empty", "false")));
        MapSqlParameterSource params = new MapSqlParameterSource();
        assertEquals("(LOWER(t.regime) = :REGIME# AND TO_NUMBER(t.legal_basis DEFAULT NULL ON CONVERSION ERROR) = :LEGAL_BASIS#)",
                where(params, or("US_SDN - REGIME", "equals", "833")));
        assertTrue(params.getValues().containsValue("us_sdn"));
        assertEquals("(LOWER(t.regime) = :REGIME# AND LOWER(t.legal_basis) <> :LEGAL_BASIS#)", where(INSTR, or("EU - REGIME", "not equals", "x")));
        assertEquals("(LOWER(t.regime) = :REGIME# AND LOWER(t.legal_basis) LIKE :LEGAL_BASIS#)", where(INSTR, or("EU - REGIME", "contains", "x")));
        assertEquals("(LOWER(t.regime) = :REGIME# AND LOWER(t.legal_basis) NOT LIKE :LEGAL_BASIS#)", where(INSTR, or("EU - REGIME", "not contains", "x")));
        assertEquals("(LOWER(t.regime) = :REGIME# AND LOWER(t.legal_basis) LIKE :LEGAL_BASIS#)", where(INSTR, or("EU - REGIME", "starts with", "x")));
        assertEquals("(LOWER(t.regime) = :REGIME# AND LOWER(t.legal_basis) NOT LIKE :LEGAL_BASIS#)", where(INSTR, or("EU - REGIME", "not starts with", "x")));
        invalid(INSTR, or("EU - REGIME", "less than", "1"), "operator 'less than' is not supported for the regime field 'EU - REGIME'");
    }

    // ------------------------------------------------------------------ "... in all targets"

    @Test
    public void emptyInAllTargets_isinsOfTheLatestVersions() {
        Set<String> empty = new HashSet<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        Set<SixSubFilters> filters = subs(sub(or("EU - REGIME", "empty in all targets", "true"), or("UK - REGIME", "empty in all targets", "false"),
                or("US - REGIME", "is empty", "true"), or("ACTIVE_FLAG", "empty in all targets", "true")));

        assertEquals("", builder.buildWhereClause(filters, params, BASE, INSTR, empty, new HashSet<>()));

        assertEquals(Collections.singleton("XS1"), empty);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).query(sql.capture(), any(MapSqlParameterSource.class), any(RowMapper.class));
        String emptyQuery = normalized(sql.getAllValues().get(0));
        assertTrue(emptyQuery, emptyQuery.startsWith("SELECT /*+ LEADING(b u) USE_HASH(u) NO_MERGE(b) NO_MERGE(u) NO_PUSH_PRED(u) */"
                + " DISTINCT b.ISIN FROM (" + BASE + ") b JOIN ("));
        assertTrue(emptyQuery, emptyQuery.contains(" AND LOWER(t.regime) IN (:REGIME#)"));
        assertTrue(emptyQuery, emptyQuery.endsWith(") u ON u.six_isin = b.ISIN"));
        assertTrue(normalized(sql.getAllValues().get(1)), normalized(sql.getAllValues().get(1))
                .contains(" AND LOWER(t.regime) NOT IN (:REGIME#) AND t.legal_basis IS NULL"));
        assertTrue(params.getValues().containsValue(Collections.singletonList("eu")));
        assertTrue(params.getValues().containsValue(Collections.singletonList("uk")));
    }

    @Test
    public void emptyInAllTargets_onlyEmpty_orOnlyNotEmpty_perRootIsin() {
        builder.buildWhereClause(subs(sub(or("EU - REGIME", "empty in all targets", "true"))), new MapSqlParameterSource(), BASE, STRUCT,
                new HashSet<>(), new HashSet<>());
        builder.buildWhereClause(subs(sub(or("EU - REGIME", "empty in all targets", "false"))), new MapSqlParameterSource(), BASE, OPT,
                new HashSet<>(), new HashSet<>());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).query(sql.capture(), any(MapSqlParameterSource.class), any(RowMapper.class));
        assertTrue(sql.getAllValues().get(0).endsWith("u.six_isin = b.HOST_ISIN"));
        assertTrue(sql.getAllValues().get(1).endsWith("u.six_isin = b.ISIN_OPTION"));
        invalid("i.other", or("EU - REGIME", "empty in all targets", "true"), "Invalid root isin: i.other");
    }

    @Test
    public void containsInAllTargets() {
        Set<String> contains = new HashSet<>();
        builder.buildWhereClause(subs(sub(or("EU - REGIME", "contains in all targets", "833"))), new MapSqlParameterSource(), BASE, INSTR,
                new HashSet<>(), contains);
        assertEquals(Collections.singleton("XS1"), contains);
        builder.buildWhereClause(subs(sub(or("EU - REGIME", "contains in all targets", "833"), or("UK - REGIME", "contains in all targets", null),
                or("US - REGIME", "equals", "x"), or("ACTIVE_FLAG", "contains in all targets", "y"))),
                new MapSqlParameterSource(), BASE, INSTR, new HashSet<>(), new HashSet<>());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).query(sql.capture(), any(MapSqlParameterSource.class), any(RowMapper.class));
        String one = normalized(sql.getAllValues().get(0));
        assertTrue(one, one.contains(" AND LOWER(t.regime) = :REGIME# AND LOWER(t.legal_basis) NOT LIKE :LEGAL_BASIS#"));
        String two = normalized(sql.getAllValues().get(1));
        assertTrue(two, two.contains(" AND (LOWER(t.regime) = :REGIME# AND LOWER(t.legal_basis) NOT LIKE :LEGAL_BASIS# OR LOWER(t.regime) = :REGIME#"));

        // only non-regime rows: nothing to read
        builder.buildWhereClause(subs(sub(or("ACTIVE_FLAG", "contains in all targets", "y"))), new MapSqlParameterSource(), BASE, INSTR,
                new HashSet<>(), new HashSet<>());
        verify(jdbc, times(2)).query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class));
    }

    // ------------------------------------------------------------------ base conditions (same-ISIN merge)

    @Test
    public void baseWhereClause_selectionSubFiltersLeftOut() {
        Set<SixSubFilters> filters = subs(
                sub(or("ACTIVE_FLAG", "equals", "Y")),
                sub(or("UK - REGIME", "is not empty", "true"), or("ACTIVE_FLAG", "equals", "N")),
                sub(or("EU - REGIME", "empty in all targets", "true")),
                sub(or("EU - REGIME", "contains in all targets", "x")));
        assertEquals("LOWER(i.active_flag) = :ACTIVE_FLAG#",
                normalized(builder.buildBaseWhereClause(filters, new MapSqlParameterSource(), INSTR)));
        verify(jdbc, never()).query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class));
    }

    @Test
    public void isSelectionSubFilter() {
        assertTrue(DynamicSqlBuilder.isSelectionSubFilter(sub(or("EU - REGIME", "equals", "x"))));
        assertTrue(DynamicSqlBuilder.isSelectionSubFilter(sub(or("ACTIVE_FLAG", "empty in all targets", "x"))));
        assertTrue(DynamicSqlBuilder.isSelectionSubFilter(sub(or("ACTIVE_FLAG", "contains in all targets", "x"))));
        assertFalse(DynamicSqlBuilder.isSelectionSubFilter(sub(or(null, "equals", "x"), or("ACTIVE_FLAG", null, null))));
    }

    @Test
    public void subFilterWithoutRow_noCondition() {
        assertEquals("", builder.buildWhereClause(subs(sub()), new MapSqlParameterSource(), BASE, INSTR, new HashSet<>(), new HashSet<>()));
    }

    /** Column names of an entity: @Column names only (a @Column without name, or an interface, gives none). */
    @Test
    public void columnNames_onlyNamedColumns() {
        Set<String> names = ReflectionTestUtils.invokeMethod(DynamicSqlBuilder.class, "columnNames", Annotated.class);
        assertEquals(Collections.singleton("NAMED"), names);
        Set<String> none = ReflectionTestUtils.invokeMethod(DynamicSqlBuilder.class, "columnNames", Runnable.class);
        assertTrue(none.isEmpty());
    }

    /** Test entity for columnNames. */
    static class Annotated {
        @Column(name = "\"named\"")
        String named;
        @Column
        String unnamed;
        String plain;
    }
}
