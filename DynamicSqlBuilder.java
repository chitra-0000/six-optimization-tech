package com.bnpp.regliss.importer.six.extractor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// TODO: the project imports were not visible in the screenshots.
// Re-add them in the IDE (Alt+Enter / Optimize Imports) for:
// ReglissBatchProfile, ReglissException, SixSubFilters, SixSubORFilters

@Component
@ReglissBatchProfile
public class DynamicSqlBuilder {

    private static final Map<String, String> OPERATOR_SQL = new HashMap<>();
    private static final Map<String, String> FIELD_TO_COLUMN = new HashMap<>();
    private static final String NULL = "IS NULL";
    private static final String NOT_NULL = "IS NOT NULL";
    private static final String LEGAL_BASIS = "LEGAL_BASIS";
    private static final String REGIME = "REGIME";
    private static final String REGIME_FIELD = " - REGIME";
    private static final String EQUALS = "equals";
    private static final String NOT_EQUALS = "not equals";
    private static final String IS_EMPTY = "is empty";
    private static final String IS_NOT_EMPTY = "is not empty";
    private static final String CONTAINS = "contains";
    private static final String NON_CONTAINS = "not contains";
    private static final String STARTS_WITH = "starts with";
    private static final String NOT_STARTS_WITH = "not starts with";
    private static final String AND = " AND ";
    private static final String OR = " OR ";
    private static final String NOT_IN = " NOT IN ";

    // TODO: the first line of each *_PART_QUERY below was cut off at the right edge of the screenshot.
    //       Everything after the index name is a best guess - copy the real line from the IDE.
    private static final String INSTR_PART_QUERY = " IN (SELECT /*+ INDEX(t IDX_SIX_TARGET_SANC_REGIME_LB) INDEX(i IDX_SIX_INSTRUMENT_LIST_VER_ISIN_CON_ACT) */ i.ISIN FROM SIX_INSTRUMENTS i JOIN SIX_TARGET t ON i.ID = t.SIX_INSTRU_ID " +
            " WHERE i.VERSION_ID = (SELECT MAX(j.VERSION_ID) FROM SIX_INSTRUMENTS j)" +
            " AND TRIM(i.isin) IS NOT NULL ";
    private static final String STRUCT_PART_QUERY = " UNION SELECT /*+ INDEX(t IDX_SIX_TARGET_SANC_REGIME_LB) INDEX(i IDX_SIX_STRUCTURE_LIST_VER_ISIN_CON_ACT) */ i.HOST_ISIN FROM SIX_STRUCTURED i JOIN SIX_TARGET t ON i.ID = t.SIX_STRUCT_ID " +
            " WHERE i.VERSION_ID = (SELECT MAX(j.VERSION_ID) FROM SIX_STRUCTURED j)" +
            " AND TRIM(i.host_isin) IS NOT NULL ";
    private static final String OPT_PART_QUERY = " UNION SELECT /*+ INDEX(t IDX_SIX_TARGET_SANC_REGIME_LB) INDEX(i IDX_SIX_OPT_LIST_VER_ISIN_CON_ACT) */ i.ISIN_OPTION FROM SIX_OPTION i JOIN SIX_TARGET t ON i.ID = t.SIX_OPT_ID " +
            " WHERE i.VERSION_ID = (SELECT MAX(j.VERSION_ID) FROM SIX_OPTION j)" +
            " AND TRIM(i.isin_option) IS NOT NULL ";

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Value("${six.exclusion.list}")
    private String sixExclusionList;

    static {
        OPERATOR_SQL.put(EQUALS,              "= :%s");
        OPERATOR_SQL.put(NOT_EQUALS,          "<> :%s");
        OPERATOR_SQL.put(IS_EMPTY,            NULL);
        OPERATOR_SQL.put(IS_NOT_EMPTY,        NOT_NULL);
        OPERATOR_SQL.put("less than",         "< :%s");
        OPERATOR_SQL.put("greater than",      "> :%s");
        OPERATOR_SQL.put("less or equal",     "<= :%s");
        OPERATOR_SQL.put("greater or equal",  ">= :%s");
        OPERATOR_SQL.put(CONTAINS,            "LIKE :%s");
        OPERATOR_SQL.put(NON_CONTAINS,        "NOT LIKE :%s");
        OPERATOR_SQL.put(STARTS_WITH,         "LIKE :%s");
        OPERATOR_SQL.put(NOT_STARTS_WITH,     "NOT LIKE :%s");
        OPERATOR_SQL.put("IN",                "IN (:%s)");
        OPERATOR_SQL.put(NOT_IN,              "NOT IN (:%s)");
    }

    static {
        FIELD_TO_COLUMN.put("LIST_ID", "i.list_id");
        FIELD_TO_COLUMN.put("VERSION_ID", "i.version_id");
        FIELD_TO_COLUMN.put("RECORD_ORIGIN", "i.record_origin");
        FIELD_TO_COLUMN.put("OLS_YES", "i.ols_yes");
        FIELD_TO_COLUMN.put("OLS_NO", "i.ols_no");
        FIELD_TO_COLUMN.put("LINK_ENTITY", "i.link_entity");
        FIELD_TO_COLUMN.put("LINK_CSID", "i.link_csid");
        FIELD_TO_COLUMN.put("SANCTIONED_PARENT_ENTITY", "i.sanctioned_parent_entity");
        FIELD_TO_COLUMN.put("NAME_DIRECT_ISSUER", "i.name_direct_issuer");
        FIELD_TO_COLUMN.put("CONFIDENCE_LEVEL", "i.confidence_level");
        FIELD_TO_COLUMN.put("ISIN", "i.isin");
        FIELD_TO_COLUMN.put("INSTR_NAME", "i.instr_name");
        FIELD_TO_COLUMN.put("FISN", "i.fisn");
        FIELD_TO_COLUMN.put("CH_VALOR", "i.ch_valor");
        FIELD_TO_COLUMN.put("INDICATIVE_ISSUE_DATE", "i.indicative_issue_date");
        FIELD_TO_COLUMN.put("INSTRUMENT_TYPE", "i.instrument_type");
        FIELD_TO_COLUMN.put("SANCTION_RELEVANT_ASSET_CLASS", "i.sanction_relevant_asset_class");
        FIELD_TO_COLUMN.put("MAIN_INSTRUMENT", "i.main_instrument");
        FIELD_TO_COLUMN.put("EQUITY_TYPE_OF_ISSUANCE", "i.equity_type_of_issuance");
        FIELD_TO_COLUMN.put("DENOMINATION_CURRENCY", "i.denomination_currency");
        FIELD_TO_COLUMN.put("MATURITY_DATE", "i.maturity_date");
        FIELD_TO_COLUMN.put("DEBT_LIFETIME_IN_DAYS", "i.debt_lifetime_in_days");
        FIELD_TO_COLUMN.put("ACTIVE_FLAG", "i.active_flag");
        FIELD_TO_COLUMN.put("DATE_OPENED_IN_SIX", "i.date_opened_in_six");
        FIELD_TO_COLUMN.put("SUBSTITUTE_ISSUE_DATE", "i.substitute_issue_date");
        FIELD_TO_COLUMN.put("ISSUE_DATE", "i.issue_date");
        FIELD_TO_COLUMN.put("CAPITAL_CHANGE_DATE", "i.capital_change_date");
        FIELD_TO_COLUMN.put("SEDOL", "i.sedol");
        FIELD_TO_COLUMN.put("CUSIP", "i.cusip");
        FIELD_TO_COLUMN.put("CINS", "i.cins");
        FIELD_TO_COLUMN.put("AUSTRIAN", "i.austrian");
        FIELD_TO_COLUMN.put("BELGIAN", "i.belgian");
        FIELD_TO_COLUMN.put("CANADIAN", "i.canadian");
        FIELD_TO_COLUMN.put("GERMAN", "i.german");
        FIELD_TO_COLUMN.put("DENMARK", "i.denmark");
        FIELD_TO_COLUMN.put("FRANCE_RGA", "i.france_rga");
        FIELD_TO_COLUMN.put("FRANCE_EUROCLEAR", "i.france_euroclear");
        FIELD_TO_COLUMN.put("ITALIAN", "i.italian");
        FIELD_TO_COLUMN.put("JAPANESE_CURRENT", "i.japanese_current");
        FIELD_TO_COLUMN.put("JAPANESE_NEW", "i.japanese_new");
        FIELD_TO_COLUMN.put("LUXEMBOURG", "i.luxembourg");
        FIELD_TO_COLUMN.put("NETHERLAND", "i.netherland");
        FIELD_TO_COLUMN.put("NORWEGIAN", "i.norwegian");
        FIELD_TO_COLUMN.put("SWEDISH", "i.swedish");
        FIELD_TO_COLUMN.put("XS_INT_NUMBER", "i.xs_int_number");
        FIELD_TO_COLUMN.put("PORTUGAL", "i.portugal");
        FIELD_TO_COLUMN.put("SOUTH_KOREA", "i.south_korea");
        FIELD_TO_COLUMN.put("HONG_KONG", "i.hong_kong");
        FIELD_TO_COLUMN.put("FIGI_GLOBAL_ID", "i.figi_global_id");
        FIELD_TO_COLUMN.put("FIGI_GLOBAL_SHARE_CLASS_ID", "i.figi_global_share_class_id");
        FIELD_TO_COLUMN.put("OBLIGOR_ROLE", "i.obligor_role");
        FIELD_TO_COLUMN.put("UNDERLYING_OBLIGOR", "i.underlying_obligor");
        FIELD_TO_COLUMN.put("UNDERLYING_OBLIGOR_GK", "i.underlying_obligor_gk");
        FIELD_TO_COLUMN.put("REFERENCE_CAPITAL", "i.reference_capital");
        FIELD_TO_COLUMN.put("ACTUAL_CAPITAL", "i.actual_capital");
        FIELD_TO_COLUMN.put("REGIMES", "i.regimes");
        FIELD_TO_COLUMN.put("HOST_CH", "i.host_ch");
        FIELD_TO_COLUMN.put("HOST_ISIN", "i.host_isin");
        FIELD_TO_COLUMN.put("HOST_GK", "i.host_gk");
        FIELD_TO_COLUMN.put("HOST_ISSUER_SHORTNAME", "i.host_issuer_shortname");
        FIELD_TO_COLUMN.put("DESCRIPTION", "i.description");
        FIELD_TO_COLUMN.put("UNDERLYING_CH", "i.underlying_ch");
        FIELD_TO_COLUMN.put("UNDERLYING_ISIN", "i.underlying_isin");
        FIELD_TO_COLUMN.put("UNDERLYING_GK", "i.underlying_gk");
        FIELD_TO_COLUMN.put("UNDERLYING_ISSUER_SHORTNAME", "i.underlying_issuer_shortname");
        FIELD_TO_COLUMN.put("UNDERLYING_ISSUER_GK", "i.underlying_issuer_gk");
        FIELD_TO_COLUMN.put("UNDERLYING_ISSUER_NAME", "i.underlying_issuer_name");
        FIELD_TO_COLUMN.put("CH_OPTION", "i.ch_option");
        FIELD_TO_COLUMN.put("ISIN_OPTION", "i.isin_option");
        FIELD_TO_COLUMN.put("ISSUER_GK", "i.issuer_gk");
        FIELD_TO_COLUMN.put("ISSUER_NAME", "i.issuer_name");
        FIELD_TO_COLUMN.put("EXPIRY_DATE", "i.expiry_date");
        FIELD_TO_COLUMN.put("SETTLEMENT_STYLE", "i.settlement_style");
        FIELD_TO_COLUMN.put(REGIME, "t.regime");
        FIELD_TO_COLUMN.put("SANCTIONED", "t.sanctioned");
        FIELD_TO_COLUMN.put("TARGET", "t.target");
        FIELD_TO_COLUMN.put(LEGAL_BASIS, "t.legal_basis");
    }

    public String buildWhereClause(Set<SixSubFilters> sixSubFilters, MapSqlParameterSource paramSrc,
                                   String subqueryBase, String rootIsin, Set<String> emptyIsinForAllTargetsClauses,
                                   Set<String> containsIsinforAllTargetsClauses) {
        if (sixSubFilters == null || sixSubFilters.isEmpty()) {
            return "";
        }

        String finalWhereClause = "";
        List<String> andGroups = new ArrayList<>();

        for (SixSubFilters sub : sixSubFilters) {
            boolean forAllTargets = sub.getActiveSixSubORfilters().stream()
                    .anyMatch(or -> or.getOperator().equals("empty in all targets"));
            boolean containsForAllTargets = sub.getActiveSixSubORfilters().stream()
                    .anyMatch(or -> or.getOperator().equals("contains in all targets"));
            if (forAllTargets) {
                frameAllTargetsQuery(sub, emptyIsinForAllTargetsClauses, paramSrc, subqueryBase, rootIsin);
            } else if (containsForAllTargets) {
                containsForAllTargetsQuery(sub, containsIsinforAllTargetsClauses, paramSrc, subqueryBase, rootIsin);
            } else {
                frameQuery(sub, andGroups, paramSrc, rootIsin, sixExclusionList);
            }
        }

        if (!andGroups.isEmpty()) {
            finalWhereClause = String.join(AND, andGroups);
        }

        return finalWhereClause;
    }

    private Collection<String> containsForAllTargetsQuery(SixSubFilters sub,
                                                          Set<String> containsIsinforAllTargetsClauses,
                                                          MapSqlParameterSource paramSrc, String subqueryBase, String rootIsin) {
        List<String> regimeQuery = new ArrayList<>();
        String columnName = "";
        String paramNameUUIDRegime = "";
        String paramNameUUIDLegalBasis = "";

        for (SixSubORFilters or : sub.getActiveSixSubORfilters()) {
            String columnWithAlias = qualifiedColumn(or.getFieldName());
            columnName = getColumnsName(isNumeric(or.getFilterValue()), columnWithAlias);
            paramNameUUIDRegime = paramName(REGIME);
            paramNameUUIDLegalBasis = paramName(LEGAL_BASIS);

            if (or.getFieldName().endsWith(REGIME_FIELD) && or.getOperator().equals("contains in all targets")) {
                regimeQuery.add(regimeConditionQuery(columnName, paramSrc, paramNameUUIDRegime, paramNameUUIDLegalBasis, or));
            }
        }

        if (!regimeQuery.isEmpty()) {
            String combinedRegimeQuery = (regimeQuery.size() == 1) ? regimeQuery.get(0) : "(" + String.join(OR, regimeQuery) + ")";
            containsIsinforAllTargetsClauses.addAll(containsForAllTargetsInClause(paramSrc, subqueryBase, rootIsin, combinedRegimeQuery));
        }

        return containsIsinforAllTargetsClauses;
    }

    private String regimeConditionQuery(String columnName, MapSqlParameterSource paramSrc, String paramNameUUIDRegime,
                                        String paramNameUUIDLegalBasis, SixSubORFilters or) {
        String regimeName = or.getFieldName().substring(0, or.getFieldName().trim().length() - REGIME_FIELD.length());
        paramSrc.addValue(paramNameUUIDRegime, regimeName.toLowerCase());
        paramSrc.addValue(paramNameUUIDLegalBasis, "%" + or.getFilterValue().toLowerCase() + "%");
        return columnName + " " + String.format(OPERATOR_SQL.get(EQUALS), paramNameUUIDRegime)
                + AND + getColumnsName(isNumeric(or.getFilterValue()), FIELD_TO_COLUMN.get(LEGAL_BASIS)) +
                " " + String.format(OPERATOR_SQL.get(NON_CONTAINS), paramNameUUIDLegalBasis);
    }

    private Collection<String> frameAllTargetsQuery(SixSubFilters sub, Set<String> excludeIsinForAllTargetsClauses,
                                                    MapSqlParameterSource paramSrc,
                                                    String subqueryBase, String rootIsin) {
        List<String> notEmptyClauses = new ArrayList<>();
        List<String> emptyClause = new ArrayList<>();
        String columnName = "";
        String paramNameUUIDRegime = "";

        for (SixSubORFilters or : sub.getActiveSixSubORfilters()) {
            String columnWithAlias = qualifiedColumn(or.getFieldName());
            columnName = getColumnsName(isNumeric(or.getFilterValue()), columnWithAlias);
            paramNameUUIDRegime = paramName(REGIME);

            if (or.getFieldName().endsWith(REGIME_FIELD) && or.getOperator().equals("empty in all targets")) {
                String regimeName = or.getFieldName().substring(0, or.getFieldName().trim().length() - REGIME_FIELD.length());
                boolean flag = Boolean.parseBoolean(or.getFilterValue().toLowerCase());

                if (flag) {
                    emptyClause.add(regimeName.toLowerCase());
                } else {
                    notEmptyClauses.add(regimeName.toLowerCase());
                }

            }

        }

        if (!emptyClause.isEmpty()) {
            excludeIsinForAllTargetsClauses.addAll(emptyForAllTargetsInClause(columnName, paramSrc, paramNameUUIDRegime,
                    emptyClause, subqueryBase, rootIsin));
        }
        if (!notEmptyClauses.isEmpty()) {
            excludeIsinForAllTargetsClauses.addAll(notEmptyForAllTargetsInClause(columnName, paramSrc, paramNameUUIDRegime,
                    notEmptyClauses, subqueryBase, rootIsin));
        }

        return excludeIsinForAllTargetsClauses;
    }

    private List<String> frameQuery(SixSubFilters sub, List<String> andGroups, MapSqlParameterSource paramSrc, String rootIsin,
                                    String sixExclusionList) {
        List<String> orClauses = new ArrayList<>();
        for (SixSubORFilters or : sub.getActiveSixSubORfilters()) {
            String columnWithAlias = qualifiedColumn(or.getFieldName());
            String columnName = getColumnsName(isNumeric(or.getFilterValue()), columnWithAlias);
            String paramNameUUIDLegalBasis = paramName(LEGAL_BASIS);
            String paramNameUUIDRegime = paramName(REGIME);

            if (or.getFieldName().endsWith(REGIME_FIELD)) {
                buildQueryForRegimes(columnName, paramNameUUIDRegime, paramNameUUIDLegalBasis, orClauses, paramSrc, or);
            } else {
                buildQueryForNonRegimes(columnName, rootIsin, orClauses, paramSrc, sixExclusionList, or, columnWithAlias);
            }
        }

        if (!orClauses.isEmpty()) {
            String orGroup = (orClauses.size() == 1) ? orClauses.get(0) : "(" + String.join(" OR ", orClauses) + ")";
            andGroups.add(orGroup);
        }
        return andGroups;
    }

    private List<String> emptyForAllTargetsInClause(String columnName, MapSqlParameterSource paramSrc, String paramNameUUIDRegime,
                                                    List<String> emptyClause, String subqueryBase, String rootIsin) {
        paramSrc.addValue(paramNameUUIDRegime, emptyClause.stream().collect(Collectors.joining(",")));
        String emptyForAllTargetsQuery = subqueryBase + AND + rootIsin + INSTR_PART_QUERY +
                AND + columnName + " " + String.format(OPERATOR_SQL.get("IN"), paramNameUUIDRegime) +
                STRUCT_PART_QUERY +
                AND + columnName + " " + String.format(OPERATOR_SQL.get("IN"), paramNameUUIDRegime) +
                OPT_PART_QUERY +
                AND + columnName + " " + String.format(OPERATOR_SQL.get("IN"), paramNameUUIDRegime) + ")";

        return jdbcTemplate.query(emptyForAllTargetsQuery, paramSrc, (rs, rowNum) -> rs.getString(defineColumn(rootIsin)));
    }

    private List<String> notEmptyForAllTargetsInClause(String columnName, MapSqlParameterSource paramSrc, String paramNameUUIDRegime,
                                                       List<String> notEmptyClause, String subqueryBase, String rootIsin) {
        paramSrc.addValue(paramNameUUIDRegime, notEmptyClause.stream().collect(Collectors.joining(",")));
        String notEmptyForAllTargetsQuery = subqueryBase + AND + rootIsin + INSTR_PART_QUERY +
                AND + columnName + " " + String.format(OPERATOR_SQL.get(NOT_IN), paramNameUUIDRegime) + AND +
                FIELD_TO_COLUMN.get(LEGAL_BASIS) + " " + NULL +
                STRUCT_PART_QUERY +
                AND + columnName + " " + String.format(OPERATOR_SQL.get(NOT_IN), paramNameUUIDRegime) + AND +
                FIELD_TO_COLUMN.get(LEGAL_BASIS) + " " + NULL +
                OPT_PART_QUERY +
                AND + columnName + " " + String.format(OPERATOR_SQL.get(NOT_IN), paramNameUUIDRegime) + AND +
                FIELD_TO_COLUMN.get(LEGAL_BASIS) + " " + NULL + ")";

        return jdbcTemplate.query(notEmptyForAllTargetsQuery, paramSrc, (rs, rowNum) -> rs.getString(defineColumn(rootIsin)));
    }

    private List<String> containsForAllTargetsInClause(MapSqlParameterSource paramSrc, String subqueryBase, String rootIsin,
                                                       String combinedRegimeQuery) {
        String containsForAllTargetsQuery = subqueryBase + AND + rootIsin + INSTR_PART_QUERY + AND +
                combinedRegimeQuery +
                STRUCT_PART_QUERY + AND +
                combinedRegimeQuery +
                OPT_PART_QUERY + AND +
                combinedRegimeQuery + ")";

        return jdbcTemplate.query(containsForAllTargetsQuery, paramSrc, (rs, rowNum) -> rs.getString(defineColumn(rootIsin)));
    }

    private List<String> buildQueryForRegimes(String columnName, String paramNameUUIDRegime, String paramNameUUIDLegalBasis,
                                              List<String> orClauses, MapSqlParameterSource paramSrc, SixSubORFilters or) {
        String regimeName = or.getFieldName().substring(0, or.getFieldName().trim().length() - REGIME_FIELD.length());
        String opKey = or.getOperator().toLowerCase().trim();

        if (opKey.equals(IS_EMPTY)) {
            boolean flag = Boolean.parseBoolean(or.getFilterValue().toLowerCase());
            paramSrc.addValue(paramNameUUIDRegime, regimeName.toLowerCase());
            if (flag) {
                orClauses.add("(" + columnName + " " + String.format(OPERATOR_SQL.get(EQUALS), paramNameUUIDRegime) + AND +
                        trimColumn(FIELD_TO_COLUMN.get(LEGAL_BASIS)) + NULL + ")");
            } else {
                orClauses.add("(" + columnName + " " + String.format(OPERATOR_SQL.get(EQUALS), paramNameUUIDRegime) + AND +
                        trimColumn(FIELD_TO_COLUMN.get(LEGAL_BASIS)) + NOT_NULL + ")");
            }

        } else if (opKey.equals(IS_NOT_EMPTY)) {
            boolean flag = Boolean.parseBoolean(or.getFilterValue().toLowerCase());
            paramSrc.addValue(paramNameUUIDRegime, regimeName.toLowerCase());
            if (flag) {
                orClauses.add("(" + columnName + " " + String.format(OPERATOR_SQL.get(EQUALS), paramNameUUIDRegime) + AND +
                        trimColumn(FIELD_TO_COLUMN.get(LEGAL_BASIS)) + NOT_NULL + ")");
            } else {
                orClauses.add("(" + columnName + " " + String.format(OPERATOR_SQL.get(EQUALS), paramNameUUIDRegime) + AND +
                        trimColumn(FIELD_TO_COLUMN.get(LEGAL_BASIS)) + NULL + ")");
            }

        } else if (opKey.equals(EQUALS) || opKey.equals(NOT_EQUALS)) {
            paramSrc.addValue(paramNameUUIDRegime, regimeName.toLowerCase());
            paramSrc.addValue(paramNameUUIDLegalBasis, or.getFilterValue().toLowerCase());
            orClauses.add("(" + columnName + " " + String.format(OPERATOR_SQL.get(EQUALS), paramNameUUIDRegime) + AND +
                    getColumnsName(isNumeric(or.getFilterValue()), FIELD_TO_COLUMN.get(LEGAL_BASIS)) + " " +
                    String.format(OPERATOR_SQL.get(opKey), paramNameUUIDLegalBasis) + ")");

        } else if (opKey.equals(CONTAINS) || opKey.equals(NON_CONTAINS)) {
            paramSrc.addValue(paramNameUUIDRegime, regimeName.toLowerCase());
            paramSrc.addValue(paramNameUUIDLegalBasis, "%" + or.getFilterValue().toLowerCase() + "%");
            orClauses.add("(" + columnName + " " + String.format(OPERATOR_SQL.get(EQUALS), paramNameUUIDRegime) + AND +
                    getColumnsName(isNumeric(or.getFilterValue()), FIELD_TO_COLUMN.get(LEGAL_BASIS)) + " " +
                    String.format(OPERATOR_SQL.get(opKey), paramNameUUIDLegalBasis) + ")");

        } else if (opKey.equals(STARTS_WITH) || opKey.equals(NOT_STARTS_WITH)) {
            paramSrc.addValue(paramNameUUIDRegime, regimeName.toLowerCase());
            paramSrc.addValue(paramNameUUIDLegalBasis, or.getFilterValue().toLowerCase() + "%");
            orClauses.add("(" + columnName + " " + String.format(OPERATOR_SQL.get(EQUALS), paramNameUUIDRegime) + AND +
                    getColumnsName(isNumeric(or.getFilterValue()), FIELD_TO_COLUMN.get(LEGAL_BASIS)) + " " +
                    String.format(OPERATOR_SQL.get(opKey), paramNameUUIDLegalBasis) + ")");

        }

        return orClauses;
    }

    private List<String> buildQueryForNonRegimes(String columnName, String rootIsin, List<String> orClauses, MapSqlParameterSource paramSrc,
                                                 String sixExclusionList, SixSubORFilters or, String columnWithAlias) {
        String paramNameUUID = paramName(or.getFieldName());
        String opKey = or.getOperator().toLowerCase().trim();

        switch (opKey) {
            case IS_EMPTY:                     return getQueryForIsEmpty(or, columnWithAlias, orClauses);
            case IS_NOT_EMPTY:                 return getQueryForIsNotEmpty(or, columnWithAlias, orClauses);
            case CONTAINS:                     return getQueryForContains(paramSrc, or, columnName, orClauses, paramNameUUID, opKey);
            case NON_CONTAINS:                 return getQueryForContains(paramSrc, or, columnName, orClauses, paramNameUUID, opKey);
            case STARTS_WITH:                  return getQueryForStartsWith(paramSrc, or, columnName, orClauses, paramNameUUID, opKey);
            case NOT_STARTS_WITH:              return getQueryForStartsWith(paramSrc, or, columnName, orClauses, paramNameUUID, opKey);
            case "not included in exclusion":  return getQueryForNotIncludedInExclusion(or, sixExclusionList, rootIsin, orClauses, paramSrc);
            case "included in exclusion":      return getQueryForIncludedInExclusion(or, sixExclusionList, rootIsin, orClauses, paramSrc);
            case EQUALS:                       return getQueryForCommonOperator(paramSrc, or, columnName, orClauses, paramNameUUID, opKey);
            case NOT_EQUALS:                   return getQueryForCommonOperator(paramSrc, or, columnName, orClauses, paramNameUUID, opKey);
            case "less than":                  return getQueryForCommonOperator(paramSrc, or, columnName, orClauses, paramNameUUID, opKey);
            case "greater than":               return getQueryForCommonOperator(paramSrc, or, columnName, orClauses, paramNameUUID, opKey);
            case "less or equal":              return getQueryForCommonOperator(paramSrc, or, columnName, orClauses, paramNameUUID, opKey);
            case "greater or equal":           return getQueryForCommonOperator(paramSrc, or, columnName, orClauses, paramNameUUID, opKey);
            default:
                throw new ReglissException("Invalid operator: " + opKey);
        }
    }

    private List<String> getQueryForCommonOperator(MapSqlParameterSource paramSrc, SixSubORFilters or, String columnName,
                                                   List<String> orClauses, String paramNameUUID, String opKey) {
        paramSrc.addValue(paramNameUUID, or.getFilterValue().toLowerCase());
        orClauses.add(columnName + " " + String.format(OPERATOR_SQL.get(opKey), paramNameUUID));

        return orClauses;
    }

    private List<String> getQueryForIncludedInExclusion(SixSubORFilters or, String sixExclusionList, String rootIsin,
                                                        List<String> orClauses, MapSqlParameterSource paramSrc) {
        boolean flag = Boolean.parseBoolean(or.getFilterValue().toLowerCase());
        String clause = flag
                ? buildIncludedInClause(rootIsin, paramSrc, sixExclusionList)
                : buildNotIncludedInClause(rootIsin, paramSrc, sixExclusionList);
        orClauses.add(clause);

        return orClauses;
    }

    private List<String> getQueryForNotIncludedInExclusion(SixSubORFilters or, String sixExclusionList, String rootIsin, List<String> orClauses,
                                                           MapSqlParameterSource paramSrc) {
        boolean flag = Boolean.parseBoolean(or.getFilterValue().toLowerCase());
        String clause = flag
                ? buildNotIncludedInClause(rootIsin, paramSrc, sixExclusionList)
                : buildIncludedInClause(rootIsin, paramSrc, sixExclusionList);
        orClauses.add(clause);

        return orClauses;
    }

    private List<String> getQueryForStartsWith(MapSqlParameterSource paramSrc, SixSubORFilters or, String columnName, List<String> orClauses,
                                               String paramNameUUID, String opKey) {
        paramSrc.addValue(paramNameUUID, or.getFilterValue().toLowerCase() + "%");
        orClauses.add(columnName + " " + String.format(OPERATOR_SQL.get(opKey), paramNameUUID));

        return orClauses;
    }

    private List<String> getQueryForContains(MapSqlParameterSource paramSrc, SixSubORFilters or, String columnName, List<String> orClauses,
                                             String paramNameUUID, String opKey) {
        paramSrc.addValue(paramNameUUID, "%" + or.getFilterValue().toLowerCase() + "%");
        orClauses.add(columnName + " " + String.format(OPERATOR_SQL.get(opKey), paramNameUUID));

        return orClauses;
    }

    private List<String> getQueryForIsNotEmpty(SixSubORFilters or, String columnWithAlias, List<String> orClauses) {
        boolean flag = Boolean.parseBoolean(or.getFilterValue().toLowerCase());
        String clause = flag ? trimColumn(columnWithAlias) + NOT_NULL : trimColumn(columnWithAlias) + NULL;
        orClauses.add(clause);

        return orClauses;
    }

    private List<String> getQueryForIsEmpty(SixSubORFilters or, String columnWithAlias, List<String> orClauses) {
        boolean flag = Boolean.parseBoolean(or.getFilterValue().toLowerCase());
        String clause = flag ? trimColumn(columnWithAlias) + NULL : trimColumn(columnWithAlias) + NOT_NULL;
        orClauses.add(clause);
        return orClauses;
    }

    private String getColumnsName(boolean numeric, String columnWithAlias) {
        return numeric ? "TO_NUMBER(" + columnWithAlias + ")" : "LOWER(" + columnWithAlias + ")";
    }

    private String qualifiedColumn(String uiField) {
        if (uiField.trim().toUpperCase().endsWith(REGIME_FIELD)) {
            return FIELD_TO_COLUMN.getOrDefault(REGIME, uiField.trim());
        }

        return FIELD_TO_COLUMN.getOrDefault(uiField.trim().toUpperCase(), uiField.trim());
    }

    private String paramName(String column) {
        return column.replaceAll("[^A-Z0-9]", "_") + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String buildNotIncludedInClause(String rootIsin,
                                            MapSqlParameterSource paramSrc, String sixExclusionList) {
        paramSrc.addValue("sixExclusionList", sixExclusionList);

        String subQuery = "SELECT DISTINCT ci.code FROM code_isin ci JOIN record_isin ri ON ci.id = ri.isin_id " +
                "JOIN record r ON ri.record_id = r.id JOIN regliss_list l ON r.list_id = l.id " +
                "JOIN version v ON v.list_id = l.id WHERE  l.reference = :sixExclusionList " +
                "AND  trim(ci.code) is not null AND v.id = ( SELECT MAX(vv.id) FROM version vv WHERE  vv.list_id = l.id AND" +
                "  vv.status <> 'DELETED' ) " +
                "AND  r.from_version <= v.id AND  r.to_version >= v.id";

        return String.format("%s NOT IN ( %s )", rootIsin, subQuery);

    }

    private String buildIncludedInClause(String rootIsin,
                                         MapSqlParameterSource paramSrc, String sixExclusionList) {
        paramSrc.addValue("sixExclusionList", sixExclusionList);

        return String.format(
                "%s IN " +
                        "(SELECT DISTINCT ci.code " +
                        "FROM code_isin ci " +
                        "JOIN record_isin ri ON ci.id = ri.isin_id " +
                        "JOIN record r        ON ri.record_id = r.id " +
                        "JOIN regliss_list l  ON r.list_id = l.id " +
                        "JOIN version v       ON v.list_id = l.id " +
                        "WHERE l.reference = :sixExclusionList " +
                        "AND trim(ci.code) is not null AND v.id = ( " +
                        "    SELECT MAX(vv.id) " +
                        "    FROM version vv " +
                        "    WHERE vv.list_id = l.id " +
                        "      AND vv.status <> 'DELETED' " +
                        ") and r.FROM_VERSION <= v.id and r.TO_VERSION >= v.id)", rootIsin);
    }

    private boolean isNumeric(String s) { return s != null && s.matches("[-+]?\\d+(\\.\\d+)?"); }

    private String trimColumn(String columnName) { return "TRIM(" + columnName + ") "; }

    private String defineColumn(String rootIsin) {

        switch (rootIsin) {
            case "i.isin"        :    return "ISIN";
            case "i.host_isin"   :    return "HOST_ISIN";
            case "i.isin_option" :    return "ISIN_OPTION";
            default:
                throw new ReglissException("Invalid root isin: " + rootIsin);
        }
    }

}
