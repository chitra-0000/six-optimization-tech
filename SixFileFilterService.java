package com.bnpp.regliss.importer.six.extractor;

import lombok.RequiredArgsConstructor;
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
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// TODO: the project imports were collapsed ("import ...") in the screenshots.
// Re-add them in the IDE (Alt+Enter / Optimize Imports) for:
// ReglissBatchProfile, SixFilters, InstrumentFile, StructuredFile, OptionsFile, ExtractDto,
// InstrumentFileExtractor, StructuredFileExtractor, OptionsFileExtractor,
// InstrumentExtractMapper, StructureExtractMapper, OptionsExtractMapper,
// DynamicSqlBuilder, SixFiltersRepository, ReglissListRepository, BatchProgressService

@Service
@RequiredArgsConstructor
@ReglissBatchProfile
@Slf4j
public class SixFileFilterService {

    private static final String DATA_EXTRACTOR_1 = "Data extractor 1";
    private static final String DATA_EXTRACTOR_2 = "Data extractor 2";
    private static final String INSTRUMENT_FILE = "Instrument File";
    private static final String STRUCTURED_FILE = "Structured File";
    private static final String OPTIONS_FILE = "Options File";
    private static final String LIST_ID = "listId";
    private static final String VERSION_ID = "versionId";
    private static final String AND = " AND ";
    private static final String MANDATORY_SELECT = "WHERE i.LIST_ID = :listId AND i.VERSION_ID = :versionId";

    private static final String INSTR_BASE_SELECT = "SELECT /*+ INDEX(t IDX_SIX_TARGET_SANC_REGIME_LB) INDEX(i IDX_SIX_INSTRUMENT_LIST_VER_ISIN_CON_ACT) */ " +
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
            "    t.SANCTIONS_RATIONALE             AS t_sanctions_rationale " +
            " FROM SIX_INSTRUMENTS i " +
            " JOIN SIX_TARGET t ON i.ID = t.SIX_INSTRU_ID " + MANDATORY_SELECT;

    private static final String STRUCT_BASE_SELECT =
            "SELECT /*+ INDEX(t IDX_SIX_TARGET_SANC_REGIME_LB) " +
            "    INDEX(i IDX_SIX_STRUCTURE_LIST_VER_ISIN_CON_ACT) */ " +
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
            "    t.SANCTIONS_RATIONALE             AS t_sanctions_rationale " +
            " FROM SIX_STRUCTURED i " +
            " INNER JOIN SIX_TARGET t " +
            " ON i.ID = t.SIX_STRUCT_ID " + MANDATORY_SELECT;

    // NOTE: the hint below uses alias "o" but the table alias is "i" (copied as-is from the screenshot).
    private static final String OPTION_BASE_SELECT = "SELECT /*+ INDEX(t IDX_SIX_TARGET_SANC_REGIME_LB) " +
            "    INDEX(o IDX_SIX_OPT_LIST_VER_ISIN_CON_ACT) */ " +
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
            "    t.SANCTIONS_RATIONALE             AS t_sanctions_rationale " +
            " FROM SIX_OPTION i " +
            " INNER JOIN SIX_TARGET t " +
            " ON i.ID = t.SIX_OPT_ID " + MANDATORY_SELECT;

    // TODO: these three lines were cut off at the right edge of the screenshot.
    //       The endings below are a best guess - copy the real ending from the IDE.
    private static final String SUBQUERY_INSTR_BASE = "SELECT /*+ INDEX(t IDX_SIX_TARGET_SANC_REGIME_LB) INDEX(i IDX_SIX_INSTRUMENT_LIST_VER_ISIN_CON_ACT) */ DISTINCT i.ISIN FROM SIX_INSTRUMENTS i JOIN SIX_TARGET t ON i.ID = t.SIX_INSTRU_ID ";

    private static final String SUBQUERY_STRUCT_BASE = "SELECT /*+ INDEX(t IDX_SIX_TARGET_SANC_REGIME_LB) INDEX(i IDX_SIX_STRUCTURE_LIST_VER_ISIN_CON_ACT) */ DISTINCT i.HOST_ISIN FROM SIX_STRUCTURED i JOIN SIX_TARGET t ON i.ID = t.SIX_STRUCT_ID ";

    private static final String SUBQUERY_OPT_BASE = "SELECT /*+ INDEX(t IDX_SIX_TARGET_SANC_REGIME_LB) INDEX(i IDX_SIX_OPT_LIST_VER_ISIN_CON_ACT) */ DISTINCT i.ISIN_OPTION FROM SIX_OPTION i JOIN SIX_TARGET t ON i.ID = t.SIX_OPT_ID ";

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Autowired
    private DynamicSqlBuilder sqlBuilder;

    @Autowired
    private SixFiltersRepository sixFiltersRepository;

    @Autowired
    private ReglissListRepository reglissListRepository;

    @Autowired
    private BatchProgressService batchProgressService;


    @Value("${six.cmic.list}")
    private String sixCMICList;

    @Value("${six.e014071.list}")
    private String sixE014071List;

    @Value("${six.db.batch}")
    private int dbBatchSize;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<InstrumentFile> filterInstrument(List<SixFilters> sixFilters, Long listId, Long versionId, List<SixFilters> cmicSixFilters, List<SixFilters> e014071SixFilters,
                                                 Long batchJobExecutionId, double percent) {

        String rootIsin = "i.isin";
        List<String> dynamicWhereAllFilters = new ArrayList<>();
        List<String> dynamicCmicFilters = new ArrayList<>();
        List<String> dynamicE014071Filters = new ArrayList<>();

        Set<String> emptyIsinForAllTargetsClauses = new HashSet<>();
        Set<String> containsIsinforAllTargetsClauses = new HashSet<>();
        Set<String> emptyIsinForAllTargetsClausesCmic = new HashSet<>();
        Set<String> containsIsinforAllTargetsClausesCmic = new HashSet<>();
        Set<String> emptyIsinForAllTargetsClausesE014071 = new HashSet<>();
        Set<String> containsIsinforAllTargetsClausesE014071 = new HashSet<>();

        MapSqlParameterSource paramsInclude = new MapSqlParameterSource();
        MapSqlParameterSource paramsCmic = new MapSqlParameterSource();
        MapSqlParameterSource paramsE014071 = new MapSqlParameterSource();
        addParamsValue(paramsInclude, listId, versionId);
        addParamsValue(paramsCmic, listId, versionId);
        addParamsValue(paramsE014071, listId, versionId);

        for (SixFilters sixFilter : sixFilters) {
            if (isInstrumentOrExtractor(sixFilter.getFileType())) {
                dynamicWhereAllFilters.add(sqlBuilder.buildWhereClause(sixFilter.getActiveSixSubfilters(), paramsInclude, SUBQUERY_INSTR_BASE, rootIsin,
                        emptyIsinForAllTargetsClauses, containsIsinforAllTargetsClauses));
                buildInstruCmicExcludeQuery(sixFilter, cmicSixFilters, paramsCmic, rootIsin, dynamicCmicFilters, emptyIsinForAllTargetsClausesCmic, containsIsinforAllTargetsClausesCmic);
                buildInstruE014071ExcludeQuery(sixFilter, e014071SixFilters, paramsE014071, rootIsin, dynamicE014071Filters, emptyIsinForAllTargetsClausesE014071, containsIsinforAllTargetsClausesE014071);
            }
        }

        String dynamicWhereAllFilter = combineJoin(dynamicWhereAllFilters);
        String dynamicCmicFilter = combineJoin(dynamicCmicFilters);
        String dynamicE014071Filter = combineJoin(dynamicE014071Filters);

        String includeSql = INSTR_BASE_SELECT;
        if (!dynamicWhereAllFilter.isEmpty()) {
            includeSql += AND + dynamicWhereAllFilter;
        }
        log.info("Instrument include sql - {}", includeSql);
        log.info("Instrument include params - {}", paramsInclude);
        incrementProgress(batchJobExecutionId, 1);
        List<InstrumentFile> includedEntries = jdbcTemplate.query(includeSql, paramsInclude, new InstrumentFileExtractor());
        if (includedEntries != null) {
            includedEntries.removeIf(a -> emptyIsinForAllTargetsClauses.contains(a.getIsin()));
            includedEntries.removeIf(a -> containsIsinforAllTargetsClauses.contains(a.getIsin()));
        } else {
            return Collections.emptyList();
        }
        incrementProgress(batchJobExecutionId, percent / 3);

        if (!dynamicCmicFilter.isEmpty()) {
            String excludeCmicSql = "SELECT /*+ INDEX(t IDX_SIX_TARGET_SANC_REGIME_LB) INDEX(i IDX_SIX_INSTRUMENT_LIST_VER_ISIN_CON_ACT) */ i.ID AS i_id, i.ISIN AS i_isin FROM SIX_INSTRUMENTS i" +
                    " JOIN SIX_TARGET t ON i.ID = t.SIX_INSTRU_ID " + MANDATORY_SELECT + AND + dynamicCmicFilter;
            log.info("Instrument exclude cmic sql - {}", excludeCmicSql);
            log.info("Instrument exclude cmic params - {}", paramsCmic);
            List<ExtractDto> excludedCmicEntries = jdbcTemplate.query(excludeCmicSql, paramsCmic, new InstrumentExtractMapper());
            excludedCmicEntries.removeIf(a -> emptyIsinForAllTargetsClausesCmic.contains(a.getIsin()));
            excludedCmicEntries.removeIf(a -> containsIsinforAllTargetsClausesCmic.contains(a.getIsin()));
            List<Long> excludedCmicIds = excludedCmicEntries.stream().map(ExtractDto::getId).collect(Collectors.toList());
            includedEntries.removeIf(includeRule -> excludedCmicIds.contains(includeRule.getId()));

            incrementProgress(batchJobExecutionId, percent / 3);
        }

        if (!dynamicE014071Filter.isEmpty()) {
            String excludeE014071Sql = "SELECT /*+ INDEX(t IDX_SIX_TARGET_SANC_REGIME_LB) INDEX(i IDX_SIX_INSTRUMENT_LIST_VER_ISIN_CON_ACT) */ i.ID AS i_id, i.ISIN AS i_isin FROM SIX_INSTRUMENTS i" +
                    " JOIN SIX_TARGET t ON i.ID = t.SIX_INSTRU_ID " + MANDATORY_SELECT + AND + dynamicE014071Filter;
            log.info("Instrument exclude eo14071 sql - {}", excludeE014071Sql);
            log.info("Instrument exclude eo14071 params - {}", paramsE014071);
            List<ExtractDto> excludedE014071Entries = jdbcTemplate.query(excludeE014071Sql, paramsE014071, new InstrumentExtractMapper());
            excludedE014071Entries.removeIf(a -> emptyIsinForAllTargetsClausesE014071.contains(a.getIsin()));
            excludedE014071Entries.removeIf(a -> containsIsinforAllTargetsClausesE014071.contains(a.getIsin()));
            List<Long> excludedE014071Ids = excludedE014071Entries.stream().map(ExtractDto::getId).collect(Collectors.toList());
            includedEntries.removeIf(includeRule -> excludedE014071Ids.contains(includeRule.getId()));

            incrementProgress(batchJobExecutionId, percent / 3);
        }

        return includedEntries;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<StructuredFile> filterStructured(List<SixFilters> sixFilters, Long listId, Long versionId, List<SixFilters> cmicSixFilters, List<SixFilters> e014071SixFilters,
                                                 Long batchJobExecutionId, double percent) {

        String rootIsin = "i.host_isin";
        List<String> dynamicWhereAllFilters = new ArrayList<>();
        List<String> dynamicCmicFilters = new ArrayList<>();
        List<String> dynamicE014071Filters = new ArrayList<>();

        Set<String> emptyIsinForAllTargetsClauses = new HashSet<>();
        Set<String> containsIsinforAllTargetsClauses = new HashSet<>();
        Set<String> emptyIsinForAllTargetsClausesCmic = new HashSet<>();
        Set<String> containsIsinforAllTargetsClausesCmic = new HashSet<>();
        Set<String> emptyIsinForAllTargetsClausesE014071 = new HashSet<>();
        Set<String> containsIsinforAllTargetsClausesE014071 = new HashSet<>();

        MapSqlParameterSource paramsInclude = new MapSqlParameterSource();
        MapSqlParameterSource paramsCmic = new MapSqlParameterSource();
        MapSqlParameterSource paramsE014071 = new MapSqlParameterSource();
        addParamsValue(paramsInclude, listId, versionId);
        addParamsValue(paramsCmic, listId, versionId);
        addParamsValue(paramsE014071, listId, versionId);


        for (SixFilters sixFilter : sixFilters) {
            if (isStructureOrExtractor(sixFilter.getFileType())) {
                dynamicWhereAllFilters.add(sqlBuilder.buildWhereClause(sixFilter.getActiveSixSubfilters(), paramsInclude, SUBQUERY_STRUCT_BASE, rootIsin, emptyIsinForAllTargetsClauses, containsIsinforAllTargetsClauses));
                buildStructCmicExcludeQuery(sixFilter, cmicSixFilters, paramsCmic, rootIsin, dynamicCmicFilters, emptyIsinForAllTargetsClausesCmic, containsIsinforAllTargetsClausesCmic);
                buildStructE014071ExcludeQuery(sixFilter, e014071SixFilters, paramsE014071, rootIsin, dynamicE014071Filters, emptyIsinForAllTargetsClausesE014071, containsIsinforAllTargetsClausesE014071);
            }
        }

        String dynamicWhereAllFilter = combineJoin(dynamicWhereAllFilters);
        String dynamicCmicFilter = combineJoin(dynamicCmicFilters);
        String dynamicE014071Filter = combineJoin(dynamicE014071Filters);

        String includeSql = STRUCT_BASE_SELECT;
        if (!dynamicWhereAllFilter.isEmpty()) {
            includeSql += AND + dynamicWhereAllFilter;
        }
        log.info("Structure include sql - {}", includeSql);
        log.info("Structure include params - {}", paramsInclude);
        incrementProgress(batchJobExecutionId, 1);
        List<StructuredFile> includedEntries = jdbcTemplate.query(includeSql, paramsInclude, new StructuredFileExtractor());
        if (includedEntries != null) {
            includedEntries.removeIf(a -> emptyIsinForAllTargetsClauses.contains(a.getHostIsin()));
            includedEntries.removeIf(a -> containsIsinforAllTargetsClauses.contains(a.getHostIsin()));
        } else {
            return Collections.emptyList();
        }
        incrementProgress(batchJobExecutionId, percent / 3);

        if (!dynamicCmicFilter.isEmpty()) {
            String excludeCmicSql = "SELECT /*+ INDEX(t IDX_SIX_TARGET_SANC_REGIME_LB) INDEX(i IDX_SIX_STRUCTURE_LIST_VER_ISIN_CON_ACT) */" +
                    " i.ID AS i_id, i.HOST_ISIN AS i_host_isin FROM SIX_STRUCTURED i" +
                    " JOIN SIX_TARGET t ON i.ID = t.SIX_STRUCT_ID " + MANDATORY_SELECT + AND + dynamicCmicFilter;
            log.info("Structure exclude cmic sql - {}", excludeCmicSql);
            log.info("Structure exclude cmic params - {}", paramsCmic);
            List<ExtractDto> excludedCmicEntries = jdbcTemplate.query(excludeCmicSql, paramsCmic, new StructureExtractMapper());
            excludedCmicEntries.removeIf(a -> emptyIsinForAllTargetsClausesCmic.contains(a.getIsin()));
            excludedCmicEntries.removeIf(a -> containsIsinforAllTargetsClausesCmic.contains(a.getIsin()));
            List<Long> excludedCmicIds = excludedCmicEntries.stream().map(ExtractDto::getId).collect(Collectors.toList());
            includedEntries.removeIf(includeRule -> excludedCmicIds.contains(includeRule.getId()));

            incrementProgress(batchJobExecutionId, percent / 3);
        }
        if (!dynamicE014071Filter.isEmpty()) {
            String excludeE014071Sql = "SELECT /*+ INDEX(t IDX_SIX_TARGET_SANC_REGIME_LB) INDEX(i IDX_SIX_STRUCTURE_LIST_VER_ISIN_CON_ACT) */" +
                    " i.ID AS i_id, i.HOST_ISIN AS i_host_isin FROM SIX_STRUCTURED i" +
                    " INNER JOIN SIX_TARGET t ON i.ID = t.SIX_STRUCT_ID " + MANDATORY_SELECT + AND + dynamicE014071Filter;
            log.info("Structure exclude eo14071 sql - {}", excludeE014071Sql);
            log.info("Structure exclude eo14071 params - {}", paramsE014071);
            List<ExtractDto> excludedE014071Entries = jdbcTemplate.query(excludeE014071Sql, paramsE014071, new StructureExtractMapper());
            excludedE014071Entries.removeIf(a -> emptyIsinForAllTargetsClausesE014071.contains(a.getIsin()));
            excludedE014071Entries.removeIf(a -> containsIsinforAllTargetsClausesE014071.contains(a.getIsin()));
            List<Long> excludedE014071Ids = excludedE014071Entries.stream().map(ExtractDto::getId).collect(Collectors.toList());
            includedEntries.removeIf(includeRule -> excludedE014071Ids.contains(includeRule.getId()));

            incrementProgress(batchJobExecutionId, percent / 3);
        }

        return includedEntries;

    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<OptionsFile> filterOptions(List<SixFilters> sixFilters, Long listId, Long versionId, List<SixFilters> cmicSixFilters, List<SixFilters> e014071SixFilters,
                                           Long batchJobExecutionId, double percent) {

        String rootIsin = "i.isin_option";
        List<String> dynamicWhereAllFilters = new ArrayList<>();
        List<String> dynamicCmicFilters = new ArrayList<>();
        List<String> dynamicE014071Filters = new ArrayList<>();

        Set<String> emptyIsinForAllTargetsClauses = new HashSet<>();
        Set<String> containsIsinforAllTargetsClauses = new HashSet<>();
        Set<String> emptyIsinForAllTargetsClausesCmic = new HashSet<>();
        Set<String> containsIsinforAllTargetsClausesCmic = new HashSet<>();
        Set<String> emptyIsinForAllTargetsClausesE014071 = new HashSet<>();
        Set<String> containsIsinforAllTargetsClausesE014071 = new HashSet<>();

        MapSqlParameterSource paramsInclude = new MapSqlParameterSource();
        MapSqlParameterSource paramsCmic = new MapSqlParameterSource();
        MapSqlParameterSource paramsE014071 = new MapSqlParameterSource();
        addParamsValue(paramsInclude, listId, versionId);
        addParamsValue(paramsCmic, listId, versionId);
        addParamsValue(paramsE014071, listId, versionId);

        for (SixFilters sixFilter : sixFilters) {
            if (isOptionOrExtractor(sixFilter.getFileType())) {
                dynamicWhereAllFilters.add(sqlBuilder.buildWhereClause(sixFilter.getActiveSixSubfilters(), paramsInclude, SUBQUERY_OPT_BASE, rootIsin, emptyIsinForAllTargetsClauses, containsIsinforAllTargetsClauses));
                buildOptionCmicExcludeQuery(sixFilter, cmicSixFilters, paramsCmic, rootIsin, dynamicCmicFilters, emptyIsinForAllTargetsClausesCmic, containsIsinforAllTargetsClausesCmic);
                buildOptionE014071ExcludeQuery(sixFilter, e014071SixFilters, paramsE014071, rootIsin, dynamicE014071Filters, emptyIsinForAllTargetsClausesE014071, containsIsinforAllTargetsClausesE014071);
            }
        }

        String dynamicWhereAllFilter = combineJoin(dynamicWhereAllFilters);
        String dynamicCmicFilter = combineJoin(dynamicCmicFilters);
        String dynamicE014071Filter = combineJoin(dynamicE014071Filters);

        String includeSql = OPTION_BASE_SELECT;
        if (!dynamicWhereAllFilter.isEmpty()) {
            includeSql += AND + dynamicWhereAllFilter;
        }
        log.info("Options include sql - {}", includeSql);
        log.info("Options include params - {}", paramsInclude);
        incrementProgress(batchJobExecutionId, 1);
        List<OptionsFile> includedEntries = jdbcTemplate.query(includeSql, paramsInclude, new OptionsFileExtractor());
        if (includedEntries != null) {
            includedEntries.removeIf(a -> emptyIsinForAllTargetsClauses.contains(a.getIsinOption()));
            includedEntries.removeIf(a -> containsIsinforAllTargetsClauses.contains(a.getIsinOption()));
        } else {
            return Collections.emptyList();
        }
        incrementProgress(batchJobExecutionId, percent / 3);

        if (!dynamicCmicFilter.isEmpty()) {
            String excludeCmicSql = "SELECT /*+ INDEX(t IDX_SIX_TARGET_SANC_REGIME_LB) INDEX(i IDX_SIX_OPT_LIST_VER_ISIN_CON_ACT) */ " +
                    " i.ID AS i_id, i.ISIN_OPTION AS i_isin_option FROM SIX_OPTION i" +
                    " JOIN SIX_TARGET t ON i.ID = t.SIX_OPT_ID " + MANDATORY_SELECT + AND + dynamicCmicFilter;
            log.info("Option exclude cmic sql - {}", excludeCmicSql);
            log.info("Option exclude cmic params - {}", paramsCmic);
            List<ExtractDto> excludedCmicEntries = jdbcTemplate.query(excludeCmicSql, paramsCmic, new OptionsExtractMapper());
            excludedCmicEntries.removeIf(a -> emptyIsinForAllTargetsClausesCmic.contains(a.getIsin()));
            excludedCmicEntries.removeIf(a -> containsIsinforAllTargetsClausesCmic.contains(a.getIsin()));
            List<Long> excludedCmicIds = excludedCmicEntries.stream().map(ExtractDto::getId).collect(Collectors.toList());
            includedEntries.removeIf(includeRule -> excludedCmicIds.contains(includeRule.getId()));

            incrementProgress(batchJobExecutionId, percent / 3);
        }
        if (!dynamicE014071Filter.isEmpty()) {
            String excludeE014071Sql = "SELECT /*+ INDEX(t IDX_SIX_TARGET_SANC_REGIME_LB) INDEX(i IDX_SIX_OPT_LIST_VER_ISIN_CON_ACT) */ " +
                    " i.ID AS i_id, i.ISIN_OPTION AS i_isin_option FROM SIX_OPTION i" +
                    " JOIN SIX_TARGET t ON i.ID = t.SIX_OPT_ID " + MANDATORY_SELECT + AND + dynamicE014071Filter;
            log.info("Option exclude eo14071 sql - {}", excludeE014071Sql);
            log.info("Option exclude eo14071 params - {}", paramsE014071);
            List<ExtractDto> excludedE014071Entries = jdbcTemplate.query(excludeE014071Sql, paramsE014071, new OptionsExtractMapper());
            excludedE014071Entries.removeIf(a -> emptyIsinForAllTargetsClausesE014071.contains(a.getIsin()));
            excludedE014071Entries.removeIf(a -> containsIsinforAllTargetsClausesE014071.contains(a.getIsin()));
            List<Long> excludedE014071Ids = excludedE014071Entries.stream().map(ExtractDto::getId).collect(Collectors.toList());
            includedEntries.removeIf(includeRule -> excludedE014071Ids.contains(includeRule.getId()));
            incrementProgress(batchJobExecutionId, percent / 3);
        }

        return includedEntries;
    }

    private boolean isInstrumentOrExtractor(String fileType) {
        return INSTRUMENT_FILE.equalsIgnoreCase(fileType) ||
                DATA_EXTRACTOR_1.equalsIgnoreCase(fileType) ||
                DATA_EXTRACTOR_2.equalsIgnoreCase(fileType);
    }

    private boolean isStructureOrExtractor(String fileType) {
        return STRUCTURED_FILE.equalsIgnoreCase(fileType) ||
                DATA_EXTRACTOR_1.equalsIgnoreCase(fileType) ||
                DATA_EXTRACTOR_2.equalsIgnoreCase(fileType);
    }

    private boolean isOptionOrExtractor(String fileType) {
        return OPTIONS_FILE.equalsIgnoreCase(fileType) ||
                DATA_EXTRACTOR_1.equalsIgnoreCase(fileType) ||
                DATA_EXTRACTOR_2.equalsIgnoreCase(fileType);
    }

    private String combineJoin(List<String> listAllFilters) {
        return listAllFilters.isEmpty() ? "" : String.join(AND, listAllFilters);
    }

    private void addParamsValue(MapSqlParameterSource params, Long listId, Long versionId) {
        params.addValue(LIST_ID, listId);
        params.addValue(VERSION_ID, versionId);
    }

    private List<String> buildOptionE014071ExcludeQuery(SixFilters sixFilter, List<SixFilters> e014071SixFilters, MapSqlParameterSource params, String rootIsin, List<String> dynamicE014071Filters,
                                                        Set<String> emptyIsinForAllTargetsClauses, Set<String> containsIsinforAllTargetsClauses) {

        if (sixFilter.isExcludeE014071()) {
            for (SixFilters e014071SixFilter : e014071SixFilters) {
                if (isOptionOrExtractor(e014071SixFilter.getFileType())) {
                    dynamicE014071Filters.add(sqlBuilder.buildWhereClause(e014071SixFilter.getActiveSixSubfilters(), params, SUBQUERY_OPT_BASE, rootIsin, emptyIsinForAllTargetsClauses, containsIsinforAllTargetsClauses));
                }
            }
        }
        return dynamicE014071Filters;
    }

    private List<String> buildOptionCmicExcludeQuery(SixFilters sixFilter, List<SixFilters> cmicSixFilters, MapSqlParameterSource params, String rootIsin, List<String> dynamicCmicFilters,
                                                     Set<String> emptyIsinForAllTargetsClauses, Set<String> containsIsinforAllTargetsClauses) {
        if (sixFilter.isExcludeCMIC()) {
            for (SixFilters cmicFilter : cmicSixFilters) {
                if (isOptionOrExtractor(cmicFilter.getFileType())) {
                    dynamicCmicFilters.add(sqlBuilder.buildWhereClause(cmicFilter.getActiveSixSubfilters(), params, SUBQUERY_OPT_BASE, rootIsin, emptyIsinForAllTargetsClauses, containsIsinforAllTargetsClauses));
                }
            }
        }
        return dynamicCmicFilters;
    }

    private List<String> buildStructE014071ExcludeQuery(SixFilters sixFilter, List<SixFilters> e014071SixFilters, MapSqlParameterSource params, String rootIsin, List<String> dynamicE014071Filters,
                                                        Set<String> emptyIsinForAllTargetsClauses, Set<String> containsIsinforAllTargetsClauses) {
        if (sixFilter.isExcludeE014071()) {
            for (SixFilters e014071SixFilter : e014071SixFilters) {
                if (isStructureOrExtractor(e014071SixFilter.getFileType())) {
                    dynamicE014071Filters.add(sqlBuilder.buildWhereClause(e014071SixFilter.getActiveSixSubfilters(), params, SUBQUERY_STRUCT_BASE, rootIsin, emptyIsinForAllTargetsClauses, containsIsinforAllTargetsClauses));
                }
            }
        }
        return dynamicE014071Filters;
    }

    private List<String> buildStructCmicExcludeQuery(SixFilters sixFilter, List<SixFilters> cmicSixFilters, MapSqlParameterSource params, String rootIsin, List<String> dynamicCmicFilters,
                                                     Set<String> emptyIsinForAllTargetsClauses, Set<String> containsIsinforAllTargetsClauses) {

        if (sixFilter.isExcludeCMIC()) {
            for (SixFilters cmicFilter : cmicSixFilters) {
                if (isStructureOrExtractor(cmicFilter.getFileType())) {
                    dynamicCmicFilters.add(sqlBuilder.buildWhereClause(cmicFilter.getActiveSixSubfilters(), params, SUBQUERY_STRUCT_BASE, rootIsin, emptyIsinForAllTargetsClauses, containsIsinforAllTargetsClauses));
                }
            }
        }
        return dynamicCmicFilters;
    }

    private List<String> buildInstruE014071ExcludeQuery(SixFilters sixFilter, List<SixFilters> e014071SixFilters, MapSqlParameterSource params, String rootIsin, List<String> dynamicE014071Filters,
                                                        Set<String> emptyIsinForAllTargetsClauses, Set<String> containsIsinforAllTargetsClauses) {

        if (sixFilter.isExcludeE014071()) {
            for (SixFilters e014071SixFilter : e014071SixFilters) {
                if (isInstrumentOrExtractor(e014071SixFilter.getFileType())) {
                    dynamicE014071Filters.add(sqlBuilder.buildWhereClause(e014071SixFilter.getActiveSixSubfilters(), params, SUBQUERY_INSTR_BASE, rootIsin, emptyIsinForAllTargetsClauses, containsIsinforAllTargetsClauses));
                }
            }
        }
        return dynamicE014071Filters;
    }

    private List<String> buildInstruCmicExcludeQuery(SixFilters sixFilter, List<SixFilters> cmicSixFilters, MapSqlParameterSource params, String rootIsin, List<String> dynamicCmicFilters,
                                                     Set<String> emptyIsinForAllTargetsClauses, Set<String> containsIsinforAllTargetsClauses) {

        if (sixFilter.isExcludeCMIC()) {
            for (SixFilters cmicFilter : cmicSixFilters) {
                if (isInstrumentOrExtractor(cmicFilter.getFileType())) {
                    dynamicCmicFilters.add(sqlBuilder.buildWhereClause(cmicFilter.getActiveSixSubfilters(), params, SUBQUERY_INSTR_BASE, rootIsin, emptyIsinForAllTargetsClauses, containsIsinforAllTargetsClauses));
                }
            }
        }
        return dynamicCmicFilters;
    }

    public void incrementProgress(Long batchJobExecutionId, double percent) {
        int increasedPercent = (int) percent;
        batchProgressService.incrementExportBatchForSix(batchJobExecutionId, increasedPercent);
    }
}
