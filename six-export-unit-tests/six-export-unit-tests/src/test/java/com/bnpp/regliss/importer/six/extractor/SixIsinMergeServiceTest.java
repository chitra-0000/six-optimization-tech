package com.bnpp.regliss.importer.six.extractor;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// TODO: re-add project imports for: ReglissList, SixFilters, SixSubFilters, SixFiltersRepository, InstrumentFile,
// StructuredFile, FilteredDataMapper, SixFileFilterService, SixFileKind

public class SixIsinMergeServiceTest {

    private SixIsinMergeService service;
    private NamedParameterJdbcTemplate jdbc;
    private SixFileFilterService filterService;
    private SixFiltersRepository repository;
    private ReglissList list;
    private SixSubFilters subFilter;

    @Before
    public void setUp() {
        service = new SixIsinMergeService();
        jdbc = mock(NamedParameterJdbcTemplate.class);
        filterService = mock(SixFileFilterService.class);
        repository = mock(SixFiltersRepository.class);
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbc);
        ReflectionTestUtils.setField(service, "sixFileFilterService", filterService);
        ReflectionTestUtils.setField(service, "sixFiltersRepository", repository);
        ReflectionTestUtils.setField(service, "filteredDataMapper", new FilteredDataMapper());
        ReflectionTestUtils.setField(service, "sixCMICList", "951");
        ReflectionTestUtils.setField(service, "sixE014071List", "E");
        list = mock(ReglissList.class);
        when(list.getId()).thenReturn(7L);
        when(list.getReference()).thenReturn("225");

        subFilter = mock(SixSubFilters.class);
        SixFilters filter = mock(SixFilters.class);
        when(filter.getActiveSixSubfilters()).thenReturn(Collections.singleton(subFilter));
        List<SixFilters> filters = new ArrayList<>(Collections.singletonList(filter));
        when(repository.getFiltersByListIdAndActiveTrueAndDeletedFalse(7L)).thenReturn(filters);
        when(repository.getFiltersByListRefAndActiveTrueAndDeletedFalse("951")).thenReturn(null);       // no CMIC filter
        when(repository.getFiltersByListRefAndActiveTrueAndDeletedFalse("E")).thenReturn(new ArrayList<>());
        when(jdbc.queryForList(startsWith("SELECT /*+ LEADING(i)"), any(MapSqlParameterSource.class), eq(String.class)))
                .thenReturn(Collections.singletonList("ACTIVE"));
        when(jdbc.queryForList(startsWith("SELECT u.six_isin"), any(MapSqlParameterSource.class), eq(String.class)))
                .thenReturn(Arrays.asList("DUP", "ACTIVE", "OTHER"));
    }

    private static InstrumentFile instrument(String isin) {
        InstrumentFile f = new InstrumentFile();
        f.setIsin(isin);
        return f;
    }

    private static StructuredFile structured(String isin) {
        StructuredFile f = new StructuredFile();
        f.setHostIsin(isin);
        return f;
    }

    @SuppressWarnings("unchecked")
    @Test
    public void load_onlyIsinsOfTheListWithSeveralRows_activeInstrumentsExcludedFromStructured() {
        List<InstrumentFile> instruments = Arrays.asList(instrument("DUP"), instrument("ACTIVE"), instrument("DUP"), instrument(null));
        when(filterService.instrumentContentRows(any(), any(), anyCollection())).thenReturn(instruments);
        List<StructuredFile> structured = Collections.singletonList(structured("DUP"));
        when(filterService.structuredContentRows(any(), any(), anyCollection())).thenReturn(structured);
        SixIsinMergeService.Scope instrumentScope = SixIsinMergeService.Scope.of(1L, 2L);
        SixIsinMergeService.Scope structuredScope = SixIsinMergeService.Scope.of(3L, 4L);

        SixIsinMergeContent content = service.load(list, instrumentScope, structuredScope,
                new LinkedHashSet<>(Arrays.asList("DUP", "ACTIVE", "SINGLE")));

        assertTrue(content.hasActiveInstrument("ACTIVE"));
        assertFalse(content.hasActiveInstrument("DUP"));
        assertEquals(2, content.instrumentRows("DUP").size());
        assertEquals(1, content.instrumentRows("ACTIVE").size());
        assertEquals(1, content.structuredRows("DUP").size());
        assertTrue(content.structuredRows("ACTIVE").isEmpty());
        assertEquals(2, content.mergedIsinCount());
        assertEquals(1, content.activeInstrumentCount());
        assertEquals("225", content.instrumentRows("DUP").get(0).getListRef());
        verify(subFilter).getActiveSixSubORfilters();                     // sub-filters loaded now

        ArgumentCaptor<Collection<String>> instrumentIsins = ArgumentCaptor.forClass(Collection.class);
        verify(filterService).instrumentContentRows(any(), any(), instrumentIsins.capture());
        assertEquals(Arrays.asList("DUP", "ACTIVE"), new ArrayList<>(instrumentIsins.getValue()));
        ArgumentCaptor<Collection<String>> structuredIsins = ArgumentCaptor.forClass(Collection.class);
        verify(filterService).structuredContentRows(any(), any(), structuredIsins.capture());
        assertEquals(Collections.singletonList("DUP"), new ArrayList<>(structuredIsins.getValue()));

        // second list of the same delivery: the per-version data are read once
        service.load(list, instrumentScope, structuredScope, Collections.singleton("SINGLE"));
        verify(jdbc, times(2)).queryForList(anyString(), any(MapSqlParameterSource.class), eq(String.class));
        // other versions: read again
        service.load(list, SixIsinMergeService.Scope.of(1L, 5L), structuredScope, Collections.singleton("SINGLE"));
        verify(jdbc, times(4)).queryForList(anyString(), any(MapSqlParameterSource.class), eq(String.class));
        // new run: read again
        service.startRun();
        service.load(list, SixIsinMergeService.Scope.of(1L, 5L), structuredScope, Collections.singleton("SINGLE"));
        verify(jdbc, times(6)).queryForList(anyString(), any(MapSqlParameterSource.class), eq(String.class));
        verify(filterService, times(3)).exclusionsFor(eq(SixFileKind.INSTRUMENT), anyLong(), anyLong(), any(), any());
    }

    @Test
    public void load_onlyActiveInstrumentIsins_noStructuredRead() {
        List<InstrumentFile> instruments = Collections.singletonList(instrument("ACTIVE"));
        when(filterService.instrumentContentRows(any(), any(), anyCollection())).thenReturn(instruments);
        SixIsinMergeContent content = service.load(list, SixIsinMergeService.Scope.of(1L, 2L), SixIsinMergeService.Scope.of(3L, 4L),
                Collections.singleton("ACTIVE"));
        assertEquals(1, content.instrumentRows("ACTIVE").size());
        verify(filterService, never()).structuredContentRows(any(), any(), anyCollection());
    }

    @Test
    public void load_instrumentOnly_noStructuredRead() {
        when(filterService.instrumentContentRows(any(), any(), anyCollection())).thenReturn(Collections.emptyList());
        SixIsinMergeContent content = service.load(list, SixIsinMergeService.Scope.of(1L, 2L), null, Collections.singleton("DUP"));
        assertEquals(0, content.mergedIsinCount());
        verify(filterService, never()).structuredContentRows(any(), any(), anyCollection());
        verify(filterService, never()).exclusionsFor(eq(SixFileKind.STRUCTURED), anyLong(), anyLong(), any(), any());
    }

    @Test
    public void load_nothingToMerge_noFilterRead() {
        SixIsinMergeContent content = service.load(list, SixIsinMergeService.Scope.of(1L, 2L), null, Collections.singleton("SINGLE"));
        assertTrue(content.hasActiveInstrument("ACTIVE"));
        assertEquals(0, content.mergedIsinCount());
        verify(repository, never()).getFiltersByListIdAndActiveTrueAndDeletedFalse(any());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).queryForList(sql.capture(), any(MapSqlParameterSource.class), eq(String.class));
        assertFalse(sql.getAllValues().get(1).contains("SIX_STRUCTURED"));
    }

    @Test
    public void load_structuredOnly_noActiveInstrumentQuery() {
        when(filterService.structuredContentRows(any(), any(), anyCollection())).thenReturn(Collections.emptyList());
        when(repository.getFiltersByListIdAndActiveTrueAndDeletedFalse(7L)).thenReturn(null);
        SixIsinMergeContent content = service.load(list, null, SixIsinMergeService.Scope.of(3L, 4L), Collections.singleton("DUP"));
        assertFalse(content.hasActiveInstrument("ACTIVE"));
        verify(filterService, never()).instrumentContentRows(any(), any(), anyCollection());
        verify(filterService).structuredContentRows(eq(Collections.emptyList()), any(), anyCollection());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(1)).queryForList(sql.capture(), any(MapSqlParameterSource.class), eq(String.class));
        assertFalse(sql.getValue().contains("SIX_INSTRUMENTS"));
    }

    @Test
    public void load_noScope_none() {
        assertEquals(0, service.load(list, null, null, Collections.singleton("A")).mergedIsinCount());
        assertNull(SixIsinMergeService.Scope.of(null, 1L));
        assertNull(SixIsinMergeService.Scope.of(1L, null));
        assertEquals("list 1 version 2", SixIsinMergeService.Scope.of(1L, 2L).toString());
    }

    @Test
    public void activeInstrumentSql_macroRule() {
        service.load(list, SixIsinMergeService.Scope.of(1L, 2L), null, Collections.emptySet());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).queryForList(sql.capture(), any(MapSqlParameterSource.class), eq(String.class));
        String active = sql.getAllValues().get(0);
        assertTrue(active, active.contains("UPPER(TRIM(i.ACTIVE_FLAG)) <> 'INACTIVE'"));
        assertTrue(active, active.contains("(UPPER(TRIM(t.TARGET)) = 'CMIC' AND TO_NUMBER(i.CONFIDENCE_LEVEL DEFAULT NULL ON CONVERSION ERROR) > 0)"));
        assertTrue(active, active.contains("UPPER(TRIM(t.SANCTIONED)) = 'YES' AND TO_NUMBER(i.CONFIDENCE_LEVEL DEFAULT NULL ON CONVERSION ERROR) > 25"));
        String duplicates = sql.getAllValues().get(1);
        assertTrue(duplicates, duplicates.endsWith("GROUP BY u.six_isin HAVING COUNT(*) > 1"));
    }
}
