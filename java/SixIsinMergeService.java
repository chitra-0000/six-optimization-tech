package com.bnpp.regliss.importer.six.extractor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

// TODO: re-add the project imports in the IDE (Alt+Enter / Optimize Imports) for:
// ReglissBatchProfile, ReglissList, SixFilters, SixSubFilters, SixFiltersRepository, InstrumentFile, StructuredFile,
// FilteredInstrumentFile, FilteredStructuredFile, FilteredDataMapper, SixFileFilterService, SixFileKind

/**
 * Same-ISIN merge (SIX macro rule), XML step: reads, for ONE output list, the raw rows whose content is merged into the
 * list's records (SixIsinMergeContent, used by SixIsinRecordMerger).
 *
 * Per raw version (computed once, reused for every list of the same delivery):
 *  - ISINs with an ACTIVE, valid instrument row (macro, CheckInstruIsinExists): ACTIVE_FLAG not 'Inactive' and a
 *    target CMIC with CONFIDENCE_LEVEL > 0, or a target not CMIC, SANCTIONED = 'Yes' and CONFIDENCE_LEVEL > 25.
 *    The structured rows of these ISINs are never used (macro: not imported). Fixed rule, independent of the lists;
 *  - ISINs that have more than one raw row (instrument + structured): only they can have something to merge.
 * Per list, only for its ISINs that have more than one raw row: the raw rows of these ISINs meeting the BASE
 * conditions of the list's filters (SixFileFilterService, same SQL builder as the export, regime sub-filters left out).
 * Nothing is written; no table or column is added.
 */
@Service
@ReglissBatchProfile
@Slf4j
public class SixIsinMergeService {

    private static final String LIST_ID = "listId";
    private static final String VERSION_ID = "versionId";
    private static final String STRUCT_LIST_ID = "structListId";
    private static final String STRUCT_VERSION_ID = "structVersionId";
    private static final String CONFIDENCE = "TO_NUMBER(i.CONFIDENCE_LEVEL DEFAULT NULL ON CONVERSION ERROR)";

    private static final String ACTIVE_INSTRUMENT_ISINS = "SELECT " + SixFileKind.INSTRUMENT.joinHint() + " DISTINCT i.ISIN"
            + " FROM SIX_INSTRUMENTS i JOIN SIX_TARGET t ON i.ID = t.SIX_INSTRU_ID"
            + " WHERE i.LIST_ID = :" + LIST_ID + " AND i.VERSION_ID = :" + VERSION_ID + " AND TRIM(i.ISIN) IS NOT NULL"
            + " AND UPPER(TRIM(i.ACTIVE_FLAG)) <> 'INACTIVE'"
            + " AND ((UPPER(TRIM(t.TARGET)) = 'CMIC' AND " + CONFIDENCE + " > 0)"
            + " OR (UPPER(TRIM(t.TARGET)) <> 'CMIC' AND UPPER(TRIM(t.SANCTIONED)) = 'YES' AND " + CONFIDENCE + " > 25))";

    private static final String INSTRUMENT_ISINS = "SELECT /*+ INDEX(i " + SixFileKind.INSTRUMENT.getVersionIndex() + ") */"
            + " i.ISIN AS six_isin FROM SIX_INSTRUMENTS i WHERE i.LIST_ID = :" + LIST_ID + " AND i.VERSION_ID = :" + VERSION_ID
            + " AND TRIM(i.ISIN) IS NOT NULL";
    private static final String STRUCTURED_ISINS = "SELECT /*+ INDEX(i " + SixFileKind.STRUCTURED.getVersionIndex() + ") */"
            + " i.HOST_ISIN AS six_isin FROM SIX_STRUCTURED i WHERE i.LIST_ID = :" + STRUCT_LIST_ID
            + " AND i.VERSION_ID = :" + STRUCT_VERSION_ID + " AND TRIM(i.HOST_ISIN) IS NOT NULL";

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;
    @Autowired
    private SixFileFilterService sixFileFilterService;
    @Autowired
    private SixFiltersRepository sixFiltersRepository;
    @Autowired
    private FilteredDataMapper filteredDataMapper;

    @Value("${six.cmic.list}")
    private String sixCMICList;

    @Value("${six.e014071.list}")
    private String sixE014071List;

    /** Data of the raw versions of the last delivery built (the lists of one delivery use the same versions). */
    private VersionData lastVersionData;

    /** Raw list and version of one file type (from SIX_FILTERED_POLLER). */
    public static final class Scope {
        private final long listId;
        private final long versionId;

        private Scope(long listId, long versionId) {
            this.listId = listId;
            this.versionId = versionId;
        }

        /** Null when the list or the version is unknown (nothing can be read for this file type). */
        public static Scope of(Long listId, Long versionId) {
            return listId == null || versionId == null ? null : new Scope(listId, versionId);
        }

        @Override
        public String toString() {
            return "list " + listId + " version " + versionId;
        }
    }

    /**
     * Content to merge into the records of one output list.
     *
     * @param outputList the output list (its filters are read again: same active filters as the export)
     * @param instrument raw instrument list / version of the build (null: no instrument file in this build)
     * @param structured raw structured list / version of the build (null: no structured file in this build)
     * @param outputIsins ISINs of the list's filtered rows (SixIsinRecordMerger.outputIsins)
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SixIsinMergeContent load(ReglissList outputList, Scope instrument, Scope structured, Set<String> outputIsins) {
        if (instrument == null && structured == null) {
            return SixIsinMergeContent.none();
        }
        long start = System.currentTimeMillis();
        VersionData data = versionData(instrument, structured);
        Set<String> candidates = new LinkedHashSet<>(outputIsins);
        candidates.retainAll(data.duplicateIsins);
        if (candidates.isEmpty()) {
            log.info("SIX same-ISIN merge, list {}: nothing to merge ({} active instrument ISINs)", outputList.getReference(),
                    data.activeInstrumentIsins.size());
            return new SixIsinMergeContent(data.activeInstrumentIsins, Collections.emptyMap(), Collections.emptyMap());
        }
        List<SixFilters> filters = filtersOf(outputList);
        String reference = outputList.getReference();

        Map<String, List<FilteredInstrumentFile>> instrumentRows = Collections.emptyMap();
        if (instrument != null) {
            instrumentRows = byIsin(sixFileFilterService.instrumentContentRows(filters, data.instrumentExclusions, candidates),
                    row -> filteredDataMapper.filteredInstrumentFile(row, reference), FilteredInstrumentFile::getIsin);
        }
        Map<String, List<FilteredStructuredFile>> structuredRows = Collections.emptyMap();
        Set<String> structuredCandidates = new LinkedHashSet<>(candidates);
        structuredCandidates.removeAll(data.activeInstrumentIsins);
        if (structured != null && !structuredCandidates.isEmpty()) {
            structuredRows = byIsin(sixFileFilterService.structuredContentRows(filters, data.structuredExclusions, structuredCandidates),
                    row -> filteredDataMapper.filteredStructuredFile(row, reference), FilteredStructuredFile::getHostIsin);
        }
        SixIsinMergeContent content = new SixIsinMergeContent(data.activeInstrumentIsins, instrumentRows, structuredRows);
        log.info("SIX same-ISIN merge, list {}: {} ISINs with several rows, {} with rows to merge, {} active instrument ISINs,"
                        + " read in {} ms", reference, candidates.size(), content.mergedIsinCount(), content.activeInstrumentCount(),
                System.currentTimeMillis() - start);
        return content;
    }

    /**
     * Called at the start of each XML step run: the per-version data are only reused inside one run (a filter of the
     * CMIC / E014071 list may change between two runs).
     */
    public synchronized void startRun() {
        lastVersionData = null;
    }

    // ------------------------------------------------------------------------------------------ per raw version

    private synchronized VersionData versionData(Scope instrument, Scope structured) {
        String key = instrument + " / " + structured;
        if (lastVersionData == null || !lastVersionData.key.equals(key)) {
            lastVersionData = new VersionData(key, instrument, structured);
        }
        return lastVersionData;
    }

    private final class VersionData {
        final String key;
        final Set<String> activeInstrumentIsins;
        final Set<String> duplicateIsins;
        final SixFileFilterService.Exclusions instrumentExclusions;
        final SixFileFilterService.Exclusions structuredExclusions;

        VersionData(String key, Scope instrument, Scope structured) {
            this.key = key;
            this.activeInstrumentIsins = instrument == null ? Collections.emptySet() : activeInstrumentIsins(instrument);
            this.duplicateIsins = duplicateIsins(instrument, structured);
            // CMIC / E014071 exclusions: computed only if a list's filter asks for them (SixFileFilterService)
            List<SixFilters> cmic = loaded(sixFiltersRepository.getFiltersByListRefAndActiveTrueAndDeletedFalse(sixCMICList));
            List<SixFilters> e014071 = loaded(sixFiltersRepository.getFiltersByListRefAndActiveTrueAndDeletedFalse(sixE014071List));
            this.instrumentExclusions = instrument == null ? null
                    : sixFileFilterService.exclusionsFor(SixFileKind.INSTRUMENT, instrument.listId, instrument.versionId, cmic, e014071);
            this.structuredExclusions = structured == null ? null
                    : sixFileFilterService.exclusionsFor(SixFileKind.STRUCTURED, structured.listId, structured.versionId, cmic, e014071);
            log.info("SIX same-ISIN merge, raw versions {}: {} active instrument ISINs, {} ISINs with several rows", key,
                    activeInstrumentIsins.size(), duplicateIsins.size());
        }
    }

    private Set<String> activeInstrumentIsins(Scope instrument) {
        MapSqlParameterSource params = new MapSqlParameterSource(LIST_ID, instrument.listId).addValue(VERSION_ID, instrument.versionId);
        return new HashSet<>(jdbcTemplate.queryForList(ACTIVE_INSTRUMENT_ISINS, params, String.class));
    }

    /** ISINs with more than one raw row in the instrument and structured versions together. */
    private Set<String> duplicateIsins(Scope instrument, Scope structured) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        List<String> parts = new ArrayList<>();
        if (instrument != null) {
            parts.add(INSTRUMENT_ISINS);
            params.addValue(LIST_ID, instrument.listId).addValue(VERSION_ID, instrument.versionId);
        }
        if (structured != null) {
            parts.add(STRUCTURED_ISINS);
            params.addValue(STRUCT_LIST_ID, structured.listId).addValue(STRUCT_VERSION_ID, structured.versionId);
        }
        String sql = "SELECT u.six_isin FROM (" + String.join(" UNION ALL ", parts) + ") u GROUP BY u.six_isin HAVING COUNT(*) > 1";
        return new HashSet<>(jdbcTemplate.queryForList(sql, params, String.class));
    }

    // ------------------------------------------------------------------------------------------ helpers

    /** Active filters of the output list, sub-filters loaded now (same snapshot rule as the export). */
    private List<SixFilters> filtersOf(ReglissList outputList) {
        return loaded(sixFiltersRepository.getFiltersByListIdAndActiveTrueAndDeletedFalse(outputList.getId()));
    }

    private static List<SixFilters> loaded(List<SixFilters> filters) {
        if (filters == null) {
            return Collections.emptyList();
        }
        filters.forEach(f -> f.getActiveSixSubfilters().forEach(SixSubFilters::getActiveSixSubORfilters));
        return filters;
    }

    /** Raw rows mapped to filtered rows, by ISIN, in row order. */
    private static <R, F> Map<String, List<F>> byIsin(List<R> rows, Function<R, F> mapper, Function<F, String> isinOf) {
        Map<String, List<F>> byIsin = new LinkedHashMap<>();
        for (R row : rows) {
            F mapped = mapper.apply(row);
            String isin = isinOf.apply(mapped);
            if (Objects.nonNull(isin)) {
                byIsin.computeIfAbsent(isin, k -> new ArrayList<>()).add(mapped);
            }
        }
        return byIsin;
    }
}
