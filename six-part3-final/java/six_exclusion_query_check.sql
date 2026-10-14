-- =====================================================================================================================
-- SIX export - "included / not included in exclusion" filters: before / after, to run in the INT database
-- (SQL Developer: run as script, F5). No application data is modified: SELECT and EXPLAIN PLAN (PLAN_TABLE) only.
--
-- Set the 4 values below first:
--   excl_list : value of the property six.exclusion.list
--   list_id / version_id : the STRUCTURED raw list and its latest version (13-10 log: 1165 / 1362)
-- The other conditions are those of the CMIC filter seen in the 13-10 log (14:05); the log cut the bind values,
-- adjust sanct1 / sanct2 / target / conf / active to the CMIC filter values if they differ.
-- =====================================================================================================================
DEFINE excl_list  = 'PUT_THE_six.exclusion.list_VALUE_HERE'
DEFINE list_id    = 1165
DEFINE version_id = 1362
DEFINE sanct1 = 'yes'
DEFINE sanct2 = 'no'
DEFINE target = 'russia'
DEFINE conf   = 3
DEFINE active = 'y'
SET TIMING ON
SET LINESIZE 250
SET PAGESIZE 200

-- ---------------------------------------------------------------------------------------------------------------------
-- 0. Size of the exclusion list (latest non-deleted version) - read ONCE per query by the new SQL
-- ---------------------------------------------------------------------------------------------------------------------
SELECT COUNT(*) AS exclusion_isins, COUNT(DISTINCT code) AS distinct_isins FROM (
  SELECT ci.code AS code FROM code_isin ci
    JOIN record_isin ri ON ci.id = ri.isin_id
    JOIN record r ON ri.record_id = r.id
    JOIN regliss_list l ON r.list_id = l.id
    JOIN version v ON v.list_id = l.id
   WHERE l.reference = '&excl_list' AND TRIM(ci.code) IS NOT NULL
     AND v.id = (SELECT MAX(vv.id) FROM version vv WHERE vv.list_id = l.id AND vv.status <> 'DELETED')
     AND r.from_version <= v.id AND r.to_version >= v.id);

-- ---------------------------------------------------------------------------------------------------------------------
-- 1. BEFORE (current SQL, 27 minutes on 13-10): execution plan only, do NOT run it.
--    Look for a "FILTER" step with the code_isin / record_isin / record / version tables BELOW it:
--    that sub-query is executed again for the rows of the version.
-- ---------------------------------------------------------------------------------------------------------------------
EXPLAIN PLAN SET STATEMENT_ID = 'SIX_BEFORE' FOR
SELECT /*+ LEADING(i) USE_NL(t) INDEX(i IDX_SIX_STRUCTURE_VERSION) INDEX(t IDX_SIX_TARGET_STRUCT_ID) */
       i.ID AS i_id, i.host_isin AS i_isin
  FROM SIX_STRUCTURED i JOIN SIX_TARGET t ON i.ID = t.SIX_STRUCT_ID
 WHERE i.LIST_ID = &list_id AND i.VERSION_ID = &version_id
   AND (LOWER(t.sanctioned) = '&sanct1' OR LOWER(t.sanctioned) = '&sanct2')
   AND LOWER(t.target) = '&target'
   AND TRIM(i.host_isin) IS NOT NULL
   AND TO_NUMBER(i.confidence_level DEFAULT NULL ON CONVERSION ERROR) >= &conf
   AND (LOWER(i.active_flag) = '&active' OR i.host_isin NOT IN (
          SELECT DISTINCT ci.code FROM code_isin ci JOIN record_isin ri ON ci.id = ri.isin_id
            JOIN record r ON ri.record_id = r.id JOIN regliss_list l ON r.list_id = l.id
            JOIN version v ON v.list_id = l.id
           WHERE l.reference = '&excl_list' AND trim(ci.code) is not null
             AND v.id = (SELECT MAX(vv.id) FROM version vv WHERE vv.list_id = l.id AND vv.status <> 'DELETED')
             AND r.from_version <= v.id AND r.to_version >= v.id));
SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY('PLAN_TABLE', 'SIX_BEFORE', 'BASIC +PREDICATE'));

-- ---------------------------------------------------------------------------------------------------------------------
-- 2. AFTER (new SQL, same rows): plan + run. Expected: one HASH JOIN (RIGHT) OUTER with the view "EX",
--    the exclusion tables read once; seconds instead of minutes.
-- ---------------------------------------------------------------------------------------------------------------------
EXPLAIN PLAN SET STATEMENT_ID = 'SIX_AFTER' FOR
SELECT /*+ LEADING(i t ex) USE_NL(t) USE_HASH(ex) SWAP_JOIN_INPUTS(ex) NO_MERGE(ex) NO_PUSH_PRED(ex)
           INDEX(i IDX_SIX_STRUCTURE_VERSION) INDEX(t IDX_SIX_TARGET_STRUCT_ID) */
       i.ID AS i_id, i.host_isin AS i_isin
  FROM SIX_STRUCTURED i JOIN SIX_TARGET t ON i.ID = t.SIX_STRUCT_ID
  LEFT JOIN (SELECT DISTINCT e.code FROM (
               SELECT ci.code AS code FROM code_isin ci
                 JOIN record_isin ri ON ci.id = ri.isin_id
                 JOIN record r ON ri.record_id = r.id
                 JOIN regliss_list l ON r.list_id = l.id
                 JOIN version v ON v.list_id = l.id
                WHERE l.reference = '&excl_list' AND TRIM(ci.code) IS NOT NULL
                  AND v.id = (SELECT MAX(vv.id) FROM version vv WHERE vv.list_id = l.id AND vv.status <> 'DELETED')
                  AND r.from_version <= v.id AND r.to_version >= v.id) e) ex ON ex.code = i.host_isin
 WHERE i.LIST_ID = &list_id AND i.VERSION_ID = &version_id
   AND (LOWER(t.sanctioned) = '&sanct1' OR LOWER(t.sanctioned) = '&sanct2')
   AND LOWER(t.target) = '&target'
   AND TRIM(i.host_isin) IS NOT NULL
   AND TO_NUMBER(i.confidence_level DEFAULT NULL ON CONVERSION ERROR) >= &conf
   AND (LOWER(i.active_flag) = '&active' OR (i.host_isin IS NOT NULL AND ex.code IS NULL));
SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY('PLAN_TABLE', 'SIX_AFTER', 'BASIC +PREDICATE'));

-- run it (counts joined rows, one per target; the log line "12347 rows" counted distinct instruments)
SELECT COUNT(*) AS rows_after FROM (
SELECT /*+ LEADING(i t ex) USE_NL(t) USE_HASH(ex) SWAP_JOIN_INPUTS(ex) NO_MERGE(ex) NO_PUSH_PRED(ex)
           INDEX(i IDX_SIX_STRUCTURE_VERSION) INDEX(t IDX_SIX_TARGET_STRUCT_ID) */
       i.ID AS i_id, i.host_isin AS i_isin
  FROM SIX_STRUCTURED i JOIN SIX_TARGET t ON i.ID = t.SIX_STRUCT_ID
  LEFT JOIN (SELECT DISTINCT e.code FROM (
               SELECT ci.code AS code FROM code_isin ci
                 JOIN record_isin ri ON ci.id = ri.isin_id
                 JOIN record r ON ri.record_id = r.id
                 JOIN regliss_list l ON r.list_id = l.id
                 JOIN version v ON v.list_id = l.id
                WHERE l.reference = '&excl_list' AND TRIM(ci.code) IS NOT NULL
                  AND v.id = (SELECT MAX(vv.id) FROM version vv WHERE vv.list_id = l.id AND vv.status <> 'DELETED')
                  AND r.from_version <= v.id AND r.to_version >= v.id) e) ex ON ex.code = i.host_isin
 WHERE i.LIST_ID = &list_id AND i.VERSION_ID = &version_id
   AND (LOWER(t.sanctioned) = '&sanct1' OR LOWER(t.sanctioned) = '&sanct2')
   AND LOWER(t.target) = '&target'
   AND TRIM(i.host_isin) IS NOT NULL
   AND TO_NUMBER(i.confidence_level DEFAULT NULL ON CONVERSION ERROR) >= &conf
   AND (LOWER(i.active_flag) = '&active' OR (i.host_isin IS NOT NULL AND ex.code IS NULL)));

-- ---------------------------------------------------------------------------------------------------------------------
-- 3. Same rows before / after, checked FAST (no OR, so Oracle can use an anti-join for the old form):
--    the two counts below must be equal.
-- ---------------------------------------------------------------------------------------------------------------------
SELECT COUNT(*) AS not_in_before FROM SIX_STRUCTURED i
 WHERE i.LIST_ID = &list_id AND i.VERSION_ID = &version_id
   AND i.host_isin NOT IN (SELECT ci.code FROM code_isin ci JOIN record_isin ri ON ci.id = ri.isin_id
            JOIN record r ON ri.record_id = r.id JOIN regliss_list l ON r.list_id = l.id JOIN version v ON v.list_id = l.id
           WHERE l.reference = '&excl_list' AND trim(ci.code) is not null
             AND v.id = (SELECT MAX(vv.id) FROM version vv WHERE vv.list_id = l.id AND vv.status <> 'DELETED')
             AND r.from_version <= v.id AND r.to_version >= v.id);
SELECT COUNT(*) AS not_in_after FROM SIX_STRUCTURED i
  LEFT JOIN (SELECT DISTINCT ci.code FROM code_isin ci JOIN record_isin ri ON ci.id = ri.isin_id
            JOIN record r ON ri.record_id = r.id JOIN regliss_list l ON r.list_id = l.id JOIN version v ON v.list_id = l.id
           WHERE l.reference = '&excl_list' AND TRIM(ci.code) IS NOT NULL
             AND v.id = (SELECT MAX(vv.id) FROM version vv WHERE vv.list_id = l.id AND vv.status <> 'DELETED')
             AND r.from_version <= v.id AND r.to_version >= v.id) ex ON ex.code = i.host_isin
 WHERE i.LIST_ID = &list_id AND i.VERSION_ID = &version_id
   AND i.host_isin IS NOT NULL AND ex.code IS NULL;

-- ---------------------------------------------------------------------------------------------------------------------
-- 4. "empty / contains in all targets" rules (13-10 log: 14:32 -> 16:50, more than 2 hours, preparing the E014071
--    filters). Set the regimes of the rule (UI field "<regime> - REGIME", value in lower case, e.g. eu, uk).
--    BEFORE: plan only (FILTER above the 3 UNION branches = the branches run again for the rows of the version).
--    AFTER : plan + run; the counts of 4a and 4b must be equal.
-- ---------------------------------------------------------------------------------------------------------------------
DEFINE regime1 = 'eu'
DEFINE regime2 = 'uk'
EXPLAIN PLAN SET STATEMENT_ID = 'SIX_ALLT_BEFORE' FOR
SELECT /*+ LEADING(i) USE_NL(t) */ DISTINCT i.host_isin FROM SIX_STRUCTURED i JOIN SIX_TARGET t ON i.ID = t.SIX_STRUCT_ID
  WHERE i.LIST_ID = &list_id AND i.VERSION_ID = &version_id
    AND i.host_isin IN (SELECT /*+ LEADING(i) USE_NL(t) */ i.ISIN FROM SIX_INSTRUMENTS i JOIN SIX_TARGET t ON i.ID = t.SIX_INSTRU_ID
                WHERE i.VERSION_ID = (SELECT MAX(j.VERSION_ID) FROM SIX_INSTRUMENTS j) AND TRIM(i.isin) IS NOT NULL
                  AND LOWER(t.regime) IN ('&regime1', '&regime2')
               UNION SELECT /*+ LEADING(i) USE_NL(t) */ i.HOST_ISIN FROM SIX_STRUCTURED i JOIN SIX_TARGET t ON i.ID = t.SIX_STRUCT_ID
                WHERE i.VERSION_ID = (SELECT MAX(j.VERSION_ID) FROM SIX_STRUCTURED j) AND TRIM(i.host_isin) IS NOT NULL
                  AND LOWER(t.regime) IN ('&regime1', '&regime2')
               UNION SELECT /*+ LEADING(i) USE_NL(t) */ i.ISIN_OPTION FROM SIX_OPTION i JOIN SIX_TARGET t ON i.ID = t.SIX_OPT_ID
                WHERE i.VERSION_ID = (SELECT MAX(j.VERSION_ID) FROM SIX_OPTION j) AND TRIM(i.isin_option) IS NOT NULL
                  AND LOWER(t.regime) IN ('&regime1', '&regime2'));
SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY('PLAN_TABLE', 'SIX_ALLT_BEFORE', 'BASIC +PREDICATE'));

EXPLAIN PLAN SET STATEMENT_ID = 'SIX_ALLT_AFTER' FOR
SELECT /*+ LEADING(b u) USE_HASH(u) NO_MERGE(b) NO_MERGE(u) NO_PUSH_PRED(u) */ DISTINCT b.HOST_ISIN
  FROM (SELECT /*+ LEADING(i) USE_NL(t) */ DISTINCT i.host_isin FROM SIX_STRUCTURED i JOIN SIX_TARGET t ON i.ID = t.SIX_STRUCT_ID
  WHERE i.LIST_ID = &list_id AND i.VERSION_ID = &version_id) b
  JOIN (SELECT /*+ LEADING(i) USE_NL(t) */ i.ISIN AS six_isin FROM SIX_INSTRUMENTS i JOIN SIX_TARGET t ON i.ID = t.SIX_INSTRU_ID
                WHERE i.VERSION_ID = (SELECT MAX(j.VERSION_ID) FROM SIX_INSTRUMENTS j) AND TRIM(i.isin) IS NOT NULL
                  AND LOWER(t.regime) IN ('&regime1', '&regime2')
               UNION SELECT /*+ LEADING(i) USE_NL(t) */ i.HOST_ISIN FROM SIX_STRUCTURED i JOIN SIX_TARGET t ON i.ID = t.SIX_STRUCT_ID
                WHERE i.VERSION_ID = (SELECT MAX(j.VERSION_ID) FROM SIX_STRUCTURED j) AND TRIM(i.host_isin) IS NOT NULL
                  AND LOWER(t.regime) IN ('&regime1', '&regime2')
               UNION SELECT /*+ LEADING(i) USE_NL(t) */ i.ISIN_OPTION FROM SIX_OPTION i JOIN SIX_TARGET t ON i.ID = t.SIX_OPT_ID
                WHERE i.VERSION_ID = (SELECT MAX(j.VERSION_ID) FROM SIX_OPTION j) AND TRIM(i.isin_option) IS NOT NULL
                  AND LOWER(t.regime) IN ('&regime1', '&regime2')) u ON u.six_isin = b.HOST_ISIN;
SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY('PLAN_TABLE', 'SIX_ALLT_AFTER', 'BASIC +PREDICATE'));

-- 4a. AFTER (run)
SELECT COUNT(*) AS all_targets_after FROM (
SELECT /*+ LEADING(b u) USE_HASH(u) NO_MERGE(b) NO_MERGE(u) NO_PUSH_PRED(u) */ DISTINCT b.HOST_ISIN
  FROM (SELECT /*+ LEADING(i) USE_NL(t) */ DISTINCT i.host_isin FROM SIX_STRUCTURED i JOIN SIX_TARGET t ON i.ID = t.SIX_STRUCT_ID
  WHERE i.LIST_ID = &list_id AND i.VERSION_ID = &version_id) b
  JOIN (SELECT /*+ LEADING(i) USE_NL(t) */ i.ISIN AS six_isin FROM SIX_INSTRUMENTS i JOIN SIX_TARGET t ON i.ID = t.SIX_INSTRU_ID
                WHERE i.VERSION_ID = (SELECT MAX(j.VERSION_ID) FROM SIX_INSTRUMENTS j) AND TRIM(i.isin) IS NOT NULL
                  AND LOWER(t.regime) IN ('&regime1', '&regime2')
               UNION SELECT /*+ LEADING(i) USE_NL(t) */ i.HOST_ISIN FROM SIX_STRUCTURED i JOIN SIX_TARGET t ON i.ID = t.SIX_STRUCT_ID
                WHERE i.VERSION_ID = (SELECT MAX(j.VERSION_ID) FROM SIX_STRUCTURED j) AND TRIM(i.host_isin) IS NOT NULL
                  AND LOWER(t.regime) IN ('&regime1', '&regime2')
               UNION SELECT /*+ LEADING(i) USE_NL(t) */ i.ISIN_OPTION FROM SIX_OPTION i JOIN SIX_TARGET t ON i.ID = t.SIX_OPT_ID
                WHERE i.VERSION_ID = (SELECT MAX(j.VERSION_ID) FROM SIX_OPTION j) AND TRIM(i.isin_option) IS NOT NULL
                  AND LOWER(t.regime) IN ('&regime1', '&regime2')) u ON u.six_isin = b.HOST_ISIN);

-- 4b. same ISINs, checked fast: hash semi-join forced on the old form (no OR here, so Oracle accepts it)
SELECT COUNT(*) AS all_targets_before FROM (
SELECT /*+ LEADING(i) USE_NL(t) */ DISTINCT i.host_isin FROM SIX_STRUCTURED i JOIN SIX_TARGET t ON i.ID = t.SIX_STRUCT_ID
  WHERE i.LIST_ID = &list_id AND i.VERSION_ID = &version_id
    AND i.host_isin IN (SELECT /*+ UNNEST HASH_SJ */ x.six_isin FROM (SELECT /*+ LEADING(i) USE_NL(t) */ i.ISIN AS six_isin FROM SIX_INSTRUMENTS i JOIN SIX_TARGET t ON i.ID = t.SIX_INSTRU_ID
                WHERE i.VERSION_ID = (SELECT MAX(j.VERSION_ID) FROM SIX_INSTRUMENTS j) AND TRIM(i.isin) IS NOT NULL
                  AND LOWER(t.regime) IN ('&regime1', '&regime2')
               UNION SELECT /*+ LEADING(i) USE_NL(t) */ i.HOST_ISIN FROM SIX_STRUCTURED i JOIN SIX_TARGET t ON i.ID = t.SIX_STRUCT_ID
                WHERE i.VERSION_ID = (SELECT MAX(j.VERSION_ID) FROM SIX_STRUCTURED j) AND TRIM(i.host_isin) IS NOT NULL
                  AND LOWER(t.regime) IN ('&regime1', '&regime2')
               UNION SELECT /*+ LEADING(i) USE_NL(t) */ i.ISIN_OPTION FROM SIX_OPTION i JOIN SIX_TARGET t ON i.ID = t.SIX_OPT_ID
                WHERE i.VERSION_ID = (SELECT MAX(j.VERSION_ID) FROM SIX_OPTION j) AND TRIM(i.isin_option) IS NOT NULL
                  AND LOWER(t.regime) IN ('&regime1', '&regime2')) x));

DELETE FROM PLAN_TABLE WHERE STATEMENT_ID IN ('SIX_BEFORE', 'SIX_AFTER', 'SIX_ALLT_BEFORE', 'SIX_ALLT_AFTER');
UNDEFINE excl_list list_id version_id sanct1 sanct2 target conf active regime1 regime2
