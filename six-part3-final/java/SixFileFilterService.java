package com.bnpp.regliss.importer.six.extractor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

// TODO: re-add the project imports in the IDE (Alt+Enter / Optimize Imports) for:
// ReglissBatchProfile, SixFilters, InstrumentFile, StructuredFile, OptionsFile,
// InstrumentFileExtractor, StructuredFileExtractor, OptionsFileExtractor, DynamicSqlBuilder, SixFileKind

/**
 * Applies the user filters of ONE output list to ONE raw SIX version (instrument, structured or options file).
 *
 * Same rules as before, now written once for the three file types (they were three copies of the same code):
 *  - a filter applies to a file type when its FILE_TYPE is that type ("Instrument File" ...) or "Data extractor 1/2";
 *  - the filters of the list are combined with AND; inside a filter the sub-filters with AND; inside a
 *    sub-filter the rows with OR (DynamicSqlBuilder);
 *  - "empty / contains in all targets" sub-filters give ISINs that are removed from the result;
 *  - CMIC / E014071: if a filter of the list that applies to this file type has EXCLUDE_CMIC / EXCLUDE_E014071,
 *    the rows matched by the filters of the CMIC / E014071 list are removed (minus their own "all targets" ISINs).
 *
 * What changed (same result):
 *  1. CMIC / E014071 rows are computed ONCE per raw version (Exclusions) instead of once per output list -
 *     the query is identical for every list.
 *  2. Removal with HashSets instead of List.contains inside removeIf (O(n) instead of O(n x m)).
 *  3. Join hint driven by the version (SixFileKind.joinHint) instead of INDEX(t IDX_SIX_TARGET_SANC_REGIME_LB),
 *     an index that does not start with the join column (the whole index was read for every query; the options
 *     hint also used a wrong alias "o" and was ignored).
 *  4. "all targets" sub-queries limited to the list / version being filtered (they read every version before;
 *     only ISINs of this version can be removed from this version's rows, so the result is the same).
 *  5. A filter whose sub-filters are only "all targets" rules adds no SQL condition: it is now skipped instead of
 *     producing "... AND  AND ..." (ORA-00936 before).
 */
@Service
@ReglissBatchProfile
@Slf4j
public class SixFileFilterService {

    private static final String DATA_EXTRACTOR_1 = "Data extractor 1";
    private static final String DATA_EXTRACTOR_2 = "Data extractor 2";
    private static final String LIST_ID = "listId";
    private static final String VERSION_ID = "versionId";
    private static final String AND = " AND ";
    private static final String MANDATORY_SELECT = " WHERE i.LIST_ID = :listId AND i.VERSION_ID = :versionId";

    // Column lists: unchanged (the extractors read these aliases)
    @SuppressWarnings("java:S1192")
    private static final String INSTR_COLUMNS =
            "    i.ID                              AS i_id, " +
            "    i.LIST_ID                         AS i_list_id, " +
            "    i.VERSION_ID                      AS i_version_id, " +
            "    i.RECORD_ORIGIN                   AS i_record_origin, " +
            "    i.OLS_YES                         AS i_ols_yes, " +
            "    i.OLS_NO                          AS i_ols_no, " +
            "    i.LINK_ENTITY                     AS i_link_entity, " +
            "    i.LINK_CSID                       AS i_link_csid, " +
            "    i.SANCTIONED_PARENT_ENTITY        AS i_sanctioned_parent_entity, " +
            "    i.NAME_DIRECT_ISSUER              AS i_name_direct_issuer, " +
            "    i.CONFIDENCE_LEVEL                AS i_confidence_level, " +
            "    i.ISIN                            AS i_isin, " +
            "    i.INSTR_NAME                      AS i_instr_name, " +
            "    i.FISN                            AS i_fisn, " +
            "    i.CH_VALOR                        AS i_ch_valor, " +
            "    i.INDICATIVE_ISSUE_DATE           AS i_indicative_issue_date, " +
            "    i.INSTRUMENT_TYPE                 AS i_instrument_type, " +
            "    i.SANCTION_RELEVANT_ASSET_CLASS   AS i_sanction_relevant_asset_class, " +
            "    i.MAIN_INSTRUMENT                 AS i_main_instrument, " +
            "    i.EQUITY_TYPE_OF_ISSUANCE         AS i_equity_type_of_issuance, " +
            "    i.DENOMINATION_CURRENCY           AS i_denomination_currency, " +
            "    i.MATURITY_DATE                   AS i_maturity_date, " +
            "    i.DEBT_LIFETIME_IN_DAYS           AS i_debt_lifetime_in_days, " +
            "    i.ACTIVE_FLAG                     AS i_active_flag, " +
            "    i.DATE_OPENED_IN_SIX              AS i_date_opened_in_six, " +
            "    i.SUBSTITUTE_ISSUE_DATE           AS i_substitute_issue_date, " +
            "    i.ISSUE_DATE                      AS i_issue_date, " +
            "    i.CAPITAL_CHANGE_DATE             AS i_capital_change_date, " +
            "    i.SEDOL                           AS i_sedol, " +
            "    i.CUSIP                           AS i_cusip, " +
            "    i.CINS                            AS i_cins, " +
            "    i.AUSTRIAN                        AS i_austrian, " +
            "    i.BELGIAN                         AS i_belgian, " +
            "    i.CANADIAN                        AS i_canadian, " +
            "    i.GERMAN                          AS i_german, " +
            "    i.DENMARK                         AS i_denmark, " +
            "    i.FRANCE_RGA                      AS i_france_rga, " +
            "    i.FRANCE_EUROCLEAR                AS i_france_euroclear, " +
            "    i.ITALIAN                         AS i_italian, " +
            "    i.JAPANESE_CURRENT                AS i_japanese_current, " +
            "    i.JAPANESE_NEW                    AS i_japanese_new, " +
            "    i.LUXEMBOURG                      AS i_luxembourg, " +
            "    i.NETHERLAND                      AS i_netherland, " +
            "    i.NORWEGIAN                       AS i_norwegian, " +
            "    i.SWEDISH                         AS i_swedish, " +
            "    i.XS_INT_NUMBER                   AS i_xs_int_number, " +
            "    i.PORTUGAL                        AS i_portugal, " +
            "    i.SOUTH_KOREA                     AS i_south_korea, " +
            "    i.HONG_KONG                       AS i_hong_kong, " +
            "    i.FIGI_GLOBAL_ID                  AS i_figi_global_id, " +
            "    i.FIGI_GLOBAL_SHARE_CLASS_ID      AS i_figi_global_share_class_id, " +
            "    i.OBLIGOR_ROLE                    AS i_obligor_role, " +
            "    i.UNDERLYING_OBLIGOR              AS i_underlying_obligor, " +
            "    i.UNDERLYING_OBLIGOR_GK           AS i_underlying_obligor_gk, " +
            "    i.REFERENCE_CAPITAL               AS i_reference_capital, " +
            "    i.ACTUAL_CAPITAL                  AS i_actual_capital, " +
            "    i.REGIMES                         AS i_regimes, " +
            "    t.ID                              AS t_id, " +
            "    t.SIX_INSTRU_ID                   AS t_six_instru_id, " +
            "    t.TARGET                          AS t_target, " +
            "    t.REGIME                          AS t_regime, " +
            "    t.SANCTIONED                      AS t_sanctioned, " +
            "    t.LEGAL_BASIS                     AS t_legal_basis, " +
            "    t.STATUS                          AS t_status, " +
            "    t.SANCTION_FLAG_CHANGED           AS t_sanction_flag_changed, " +
            "    t.REASON_FOR_CHANGE               AS t_reason_for_change, " +
            "    t.SANCTIONS_RATIONALE             AS t_sanctions_rationale ";

    @SuppressWarnings("java:S1192")
    private static final String STRUCT_COLUMNS =
            "    i.ID                              AS i_id, " +
            "    i.LIST_ID                         AS i_list_id, " +
            "    i.VERSION_ID                      AS i_version_id, " +
            "    i.HOST_CH                         AS i_host_ch, " +
            "    i.HOST_ISIN                       AS i_host_isin, " +
            "    i.HOST_GK                         AS i_host_gk, " +
            "    i.HOST_ISSUER_SHORTNAME           AS i_host_issuer_shortname, " +
            "    i.DESCRIPTION                     AS i_description, " +
            "    i.FISN                            AS i_fisn, " +
            "    i.DATE_OPENED_IN_SIX              AS i_date_opened_in_six, " +
            "    i.SUBSTITUTE_ISSUE_DATE           AS i_substitute_issue_date, " +
            "    i.INDICATIVE_ISSUE_DATE           AS i_indicative_issue_date, " +
            "    i.SETTLEMENT_STYLE                AS i_settlement_style, " +
            "    i.INSTRUMENT_TYPE                 AS i_instrument_type, " +
            "    i.DENOMINATION_CURRENCY           AS i_denomination_currency, " +
            "    i.MATURITY_DATE                   AS i_maturity_date, " +
            "    i.ACTIVE_FLAG                     AS i_active_flag, " +
            "    i.ISSUE_DATE                      AS i_issue_date, " +
            "    i.UNDERLYING_CH                   AS i_underlying_ch, " +
            "    i.UNDERLYING_ISIN                 AS i_underlying_isin, " +
            "    i.UNDERLYING_GK                   AS i_underlying_gk, " +
            "    i.UNDERLYING_ISSUER_SHORTNAME     AS i_underlying_issuer_shortname, " +
            "    i.SEDOL                           AS i_sedol, " +
            "    i.CUSIP                           AS i_cusip, " +
            "    i.CINS                            AS i_cins, " +
            "    i.AUSTRIAN                        AS i_austrian, " +
            "    i.BELGIAN                         AS i_belgian, " +
            "    i.CANADIAN                        AS i_canadian, " +
            "    i.GERMAN                          AS i_german, " +
            "    i.DENMARK                         AS i_denmark, " +
            "    i.FRANCE_RGA                      AS i_france_rga, " +
            "    i.FRANCE_EUROCLEAR                AS i_france_euroclear, " +
            "    i.ITALIAN                         AS i_italian, " +
            "    i.JAPANESE_CURRENT                AS i_japanese_current, " +
            "    i.JAPANESE_NEW                    AS i_japanese_new, " +
            "    i.LUXEMBOURG                      AS i_luxembourg, " +
            "    i.NETHERLAND                      AS i_netherland, " +
            "    i.NORWEGIAN                       AS i_norwegian, " +
            "    i.SWEDISH                         AS i_swedish, " +
            "    i.XS_INT_NUMBER                   AS i_xs_int_number, " +
            "    i.PORTUGAL                        AS i_portugal, " +
            "    i.SOUTH_KOREA                     AS i_south_korea, " +
            "    i.HONG_KONG                       AS i_hong_kong, " +
            "    i.FIGI_GLOBAL_ID                  AS i_figi_global_id, " +
            "    i.FIGI_GLOBAL_SHARE_CLASS_ID      AS i_figi_global_share_class_id, " +
            "    i.REGIMES                         AS i_regimes, " +
            "    i.CONFIDENCE_LEVEL                AS i_confidence_level, " +
            "    t.ID                              AS t_id, " +
            "    t.SIX_INSTRU_ID                   AS t_six_instru_id, " +
            "    t.SIX_STRUCT_ID                   AS t_six_struct_id, " +
            "    t.SIX_OPT_ID                      AS t_six_opt_id, " +
            "    t.TARGET                          AS t_target, " +
            "    t.REGIME                          AS t_regime, " +
            "    t.LEGAL_BASIS                     AS t_legal_basis, " +
            "    t.SANCTIONED                      AS t_sanctioned, " +
            "    t.STATUS                          AS t_status, " +
            "    t.SANCTION_FLAG_CHANGED           AS t_sanction_flag_changed, " +
            "    t.REASON_FOR_CHANGE               AS t_reason_for_change, " +
            "    t.SANCTIONS_RATIONALE             AS t_sanctions_rationale ";

    @SuppressWarnings("java:S1192")
    private static final String OPTION_COLUMNS =
            "    i.ID                              AS i_id, " +
            "    i.LIST_ID                         AS i_list_id, " +
            "    i.VERSION_ID                      AS i_version_id, " +
            "    i.CH_OPTION                       AS i_ch_option, " +
            "    i.ISIN_OPTION                     AS i_isin_option, " +
            "    i.DESCRIPTION                     AS i_description, " +
            "    i.FISN                            AS i_fisn, " +
            "    i.ISSUER_GK                       AS i_issuer_gk, " +
            "    i.ISSUER_NAME                     AS i_issuer_name, " +
            "    i.DATE_OPENED_IN_SIX              AS i_date_opened_in_six, " +
            "    i.EXPIRY_DATE                     AS i_expiry_date, " +
            "    i.INSTRUMENT_TYPE                 AS i_instrument_type, " +
            "    i.DENOMINATION_CURRENCY           AS i_denomination_currency, " +
            "    i.ACTIVE_FLAG                     AS i_active_flag, " +
            "    i.ISSUE_DATE                      AS i_issue_date, " +
            "    i.UNDERLYING_CH                   AS i_underlying_ch, " +
            "    i.UNDERLYING_ISIN                 AS i_underlying_isin, " +
            "    i.UNDERLYING_ISSUER_GK            AS i_underlying_issuer_gk, " +
            "    i.UNDERLYING_ISSUER_NAME          AS i_underlying_issuer_name, " +
            "    i.SEDOL                           AS i_sedol, " +
            "    i.CUSIP                           AS i_cusip, " +
            "    i.CINS                            AS i_cins, " +
            "    i.AUSTRIAN                        AS i_austrian, " +
            "    i.BELGIAN                         AS i_belgian, " +
            "    i.CANADIAN                        AS i_canadian, " +
            "    i.GERMAN                          AS i_german, " +
            "    i.DENMARK                         AS i_denmark, " +
            "    i.FRANCE_RGA                      AS i_france_rga, " +
            "    i.FRANCE_EUROCLEAR                AS i_france_euroclear, " +
            "    i.ITALIAN                         AS i_italian, " +
            "    i.JAPANESE_CURRENT                AS i_japanese_current, " +
            "    i.JAPANESE_NEW                    AS i_japanese_new, " +
            "    i.LUXEMBOURG                      AS i_luxembourg, " +
            "    i.NETHERLAND                      AS i_netherland, " +
            "    i.NORWEGIAN                       AS i_norwegian, " +
            "    i.SWEDISH                         AS i_swedish, " +
            "    i.XS_INT_NUMBER                   AS i_xs_int_number, " +
            "    i.PORTUGAL                        AS i_portugal, " +
            "    i.SOUTH_KOREA                     AS i_south_korea, " +
            "    i.HONG_KONG                       AS i_hong_kong, " +
            "    i.FIGI_GLOBAL_ID                  AS i_figi_global_id, " +
            "    i.FIGI_GLOBAL_SHARE_CLASS_ID      AS i_figi_global_share_class_id, " +
            "    i.REGIMES                         AS i_regimes, " +
            "    i.CONFIDENCE_LEVEL                AS i_confidence_level, " +
            "    t.ID                              AS t_id, " +
            "    t.SIX_INSTRU_ID                   AS t_six_instru_id, " +
            "    t.SIX_STRUCT_ID                   AS t_six_struct_id, " +
            "    t.SIX_OPT_ID                      AS t_six_opt_id, " +
            "    t.TARGET                          AS t_target, " +
            "    t.REGIME                          AS t_regime, " +
            "    t.LEGAL_BASIS                     AS t_legal_basis, " +
            "    t.SANCTIONED                      AS t_sanctioned, " +
            "    t.STATUS                          AS t_status, " +
            "    t.SANCTION_FLAG_CHANGED           AS t_sanction_flag_changed, " +
            "    t.REASON_FOR_CHANGE               AS t_reason_for_change, " +
            "    t.SANCTIONS_RATIONALE             AS t_sanctions_rationale ";

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Autowired
    private DynamicSqlBuilder sqlBuilder;

    // ------------------------------------------------------------------------------------------ API

    /**
     * CMIC / E014071 exclusions of one raw version, computed on first use and then reused for every output list.
     * One instance per export run (not kept between runs: the CMIC / E014071 filters can change).
     */
    public Exclusions exclusionsFor(SixFileKind kind, long rawListId, long versionId,
                                   List<SixFilters> cmicSixFilters, List<SixFilters> e014071SixFilters) {
        return new Exclusions(kind, rawListId, versionId, cmicSixFilters, e014071SixFilters);
    }

    /**
     * Exclusions of one raw version, computed NOW for the lists that need them (a filter of the list, for this file
     * type, has EXCLUDE_CMIC / EXCLUDE_E014071). Called once at the start of the export: an error here concerns every
     * list, so it stops the export of the delivery before any list is written.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Exclusions prepareExclusions(SixFileKind kind, long rawListId, long versionId, List<SixFilters> cmicSixFilters,
                                        List<SixFilters> e014071SixFilters, Collection<List<SixFilters>> filtersOfEveryList) {
        Exclusions exclusions = new Exclusions(kind, rawListId, versionId, cmicSixFilters, e014071SixFilters);
        boolean cmic = false;
        boolean e014071 = false;
        for (List<SixFilters> listFilters : filtersOfEveryList) {
            for (SixFilters filter : listFilters) {
                if (appliesTo(kind, filter.getFileType())) {
                    cmic |= filter.isExcludeCMIC();
                    e014071 |= filter.isExcludeE014071();
                }
            }
        }
        if (cmic) {
            exclusions.cmicIds();
        }
        if (e014071) {
            exclusions.e014071Ids();
        }
        return exclusions;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<InstrumentFile> filterInstrument(List<SixFilters> sixFilters, Exclusions exclusions) {
        return filter(SixFileKind.INSTRUMENT, sixFilters, exclusions, new InstrumentFileExtractor(),
                InstrumentFile::getId, InstrumentFile::getIsin);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<StructuredFile> filterStructured(List<SixFilters> sixFilters, Exclusions exclusions) {
        return filter(SixFileKind.STRUCTURED, sixFilters, exclusions, new StructuredFileExtractor(),
                StructuredFile::getId, StructuredFile::getHostIsin);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<OptionsFile> filterOptions(List<SixFilters> sixFilters, Exclusions exclusions) {
        return filter(SixFileKind.OPTIONS, sixFilters, exclusions, new OptionsFileExtractor(),
                OptionsFile::getId, OptionsFile::getIsinOption);
    }

    /** Rows of the raw version kept for one output list (InstrumentFile / StructuredFile / OptionsFile). */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<?> filter(SixFileKind kind, List<SixFilters> sixFilters, Exclusions exclusions) {
        switch (kind) {
            case INSTRUMENT: return filterInstrument(sixFilters, exclusions);
            case STRUCTURED: return filterStructured(sixFilters, exclusions);
            case OPTIONS:    return filterOptions(sixFilters, exclusions);
            default:         throw new IllegalArgumentException("Unsupported SIX file type " + kind);
        }
    }

    // ------------------------------------------------------------------------------------------ engine

    private <R> List<R> filter(SixFileKind kind, List<SixFilters> sixFilters, Exclusions exclusions,
                               ResultSetExtractor<List<R>> extractor, Function<R, Long> idOf, Function<R, String> isinOf) {
        if (exclusions.kind != kind) {
            throw new IllegalArgumentException("Exclusions of " + exclusions.kind + " used for " + kind);
        }
        MapSqlParameterSource params = scopeParams(exclusions.rawListId, exclusions.versionId);
        List<String> clauses = new ArrayList<>();
        Set<String> emptyInAllTargets = new HashSet<>();
        Set<String> containsInAllTargets = new HashSet<>();
        boolean excludeCmic = false;
        boolean excludeE014071 = false;

        for (SixFilters sixFilter : sixFilters) {
            if (appliesTo(kind, sixFilter.getFileType())) {
                clauses.add(sqlBuilder.buildWhereClause(sixFilter.getActiveSixSubfilters(), params, subqueryBase(kind),
                        kind.getRootIsinColumn(), emptyInAllTargets, containsInAllTargets));
                excludeCmic |= sixFilter.isExcludeCMIC();
                excludeE014071 |= sixFilter.isExcludeE014071();
            }
        }

        String where = combineJoin(clauses);
        String includeSql = includeSelect(kind) + (where.isEmpty() ? "" : AND + where);
        log.info("{} include sql - {}", kind, includeSql);
        log.debug("{} include params - {}", kind, params);

        long start = System.currentTimeMillis();
        List<R> included = jdbcTemplate.query(includeSql, params, extractor);
        if (included == null) {
            return Collections.emptyList();
        }
        int read = included.size();

        Set<Long> excludedIds = new HashSet<>();
        if (excludeCmic) {
            excludedIds.addAll(exclusions.cmicIds());
        }
        if (excludeE014071) {
            excludedIds.addAll(exclusions.e014071Ids());
        }
        included.removeIf(row -> emptyInAllTargets.contains(isinOf.apply(row))
                || containsInAllTargets.contains(isinOf.apply(row))
                || excludedIds.contains(idOf.apply(row)));

        log.info("{} filter: {} rows read, {} kept ({} 'all targets' ISINs, CMIC excluded: {}, E014071 excluded: {}) in {} ms",
                kind, read, included.size(), emptyInAllTargets.size() + containsInAllTargets.size(),
                excludeCmic, excludeE014071, System.currentTimeMillis() - start);
        return included;
    }

    /** Ids of the rows matched by the filters of the CMIC (or E014071) list, minus their own "all targets" ISINs. */
    private Set<Long> excludedIds(SixFileKind kind, long rawListId, long versionId, List<SixFilters> exclusionFilters,
                                  String label) {
        MapSqlParameterSource params = scopeParams(rawListId, versionId);
        List<String> clauses = new ArrayList<>();
        Set<String> emptyInAllTargets = new HashSet<>();
        Set<String> containsInAllTargets = new HashSet<>();
        for (SixFilters filter : exclusionFilters) {
            if (appliesTo(kind, filter.getFileType())) {
                clauses.add(sqlBuilder.buildWhereClause(filter.getActiveSixSubfilters(), params, subqueryBase(kind),
                        kind.getRootIsinColumn(), emptyInAllTargets, containsInAllTargets));
            }
        }
        String where = combineJoin(clauses);
        if (where.isEmpty()) {
            // same as before: no SQL condition -> no exclusion
            log.info("{} exclude {}: no condition for this file type, nothing excluded", kind, label);
            return Collections.emptySet();
        }
        String sql = "SELECT " + kind.joinHint() + " i.ID AS i_id, " + kind.getRootIsinColumn() + " AS i_isin"
                + fromClause(kind) + AND + where;
        log.info("{} exclude {} sql - {}", kind, label, sql);
        log.debug("{} exclude {} params - {}", kind, label, params);

        long start = System.currentTimeMillis();
        Set<Long> ids = new HashSet<>();
        jdbcTemplate.query(sql, params, rs -> {
            String isin = rs.getString("i_isin");
            if (!emptyInAllTargets.contains(isin) && !containsInAllTargets.contains(isin)) {
                ids.add(rs.getLong("i_id"));
            }
        });
        log.info("{} exclude {}: {} rows in {} ms (computed once, used for every list)", kind, label, ids.size(),
                System.currentTimeMillis() - start);
        return ids;
    }

    // ------------------------------------------------------------------------------------------ SQL parts

    /** Table and join names come from SixFileKind constants, never from input. */
    private static String fromClause(SixFileKind kind) {
        return " FROM " + kind.getTable() + " i JOIN SIX_TARGET t ON i.ID = t." + kind.getTargetFkColumn() + MANDATORY_SELECT;
    }

    private static String includeSelect(SixFileKind kind) {
        return "SELECT " + kind.joinHint() + columns(kind) + fromClause(kind);
    }

    /** Base of the "all targets" sub-queries: ISINs of the version being filtered (class comment, point 4). */
    private static String subqueryBase(SixFileKind kind) {
        return "SELECT " + kind.joinHint() + " DISTINCT " + kind.getRootIsinColumn() + fromClause(kind);
    }

    private static String columns(SixFileKind kind) {
        switch (kind) {
            case INSTRUMENT: return INSTR_COLUMNS;
            case STRUCTURED: return STRUCT_COLUMNS;
            case OPTIONS:    return OPTION_COLUMNS;
            default:         throw new IllegalArgumentException("Unsupported SIX file type " + kind);
        }
    }

    private static boolean appliesTo(SixFileKind kind, String fileType) {
        return kind.getFilterFileType().equalsIgnoreCase(fileType)
                || DATA_EXTRACTOR_1.equalsIgnoreCase(fileType)
                || DATA_EXTRACTOR_2.equalsIgnoreCase(fileType);
    }

    /** Filters joined with AND; a filter without SQL condition (only "all targets" rules, or no sub-filter) is skipped. */
    private static String combineJoin(List<String> clauses) {
        List<String> notBlank = new ArrayList<>();
        for (String clause : clauses) {
            if (clause != null && !clause.trim().isEmpty()) {
                notBlank.add(clause);
            }
        }
        return String.join(AND, notBlank);
    }

    private static MapSqlParameterSource scopeParams(long rawListId, long versionId) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue(LIST_ID, rawListId);
        params.addValue(VERSION_ID, versionId);
        return params;
    }

    /** CMIC / E014071 exclusions of one raw version (one file type): computed once, reused for every output list. */
    public final class Exclusions {
        private final SixFileKind kind;
        private final long rawListId;
        private final long versionId;
        private final List<SixFilters> cmicSixFilters;
        private final List<SixFilters> e014071SixFilters;
        private Set<Long> cmicIds;
        private Set<Long> e014071Ids;

        private Exclusions(SixFileKind kind, long rawListId, long versionId,
                           List<SixFilters> cmicSixFilters, List<SixFilters> e014071SixFilters) {
            this.kind = kind;
            this.rawListId = rawListId;
            this.versionId = versionId;
            this.cmicSixFilters = cmicSixFilters == null ? Collections.emptyList() : cmicSixFilters;
            this.e014071SixFilters = e014071SixFilters == null ? Collections.emptyList() : e014071SixFilters;
        }

        synchronized Set<Long> cmicIds() {
            if (cmicIds == null) {
                cmicIds = excludedIds(kind, rawListId, versionId, cmicSixFilters, "cmic");
            }
            return cmicIds;
        }

        synchronized Set<Long> e014071Ids() {
            if (e014071Ids == null) {
                e014071Ids = excludedIds(kind, rawListId, versionId, e014071SixFilters, "eo14071");
            }
            return e014071Ids;
        }

        public SixFileKind getKind() {
            return kind;
        }
    }
}
