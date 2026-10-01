-- ============================================================================
-- Checks for V2_471 (read-only). Not a Flyway migration.
-- ============================================================================

-- 1. BEFORE deploying: must list the 4 SIX tables. No rows = the application
--    user does not own the tables -> set six.import.id-sequence.* properties.
--    Note the CACHE_SIZE values (expected 20) - the rollback restores them.
SELECT c.TABLE_NAME, s.SEQUENCE_NAME, s.CACHE_SIZE
  FROM USER_TAB_IDENTITY_COLS c
  JOIN USER_SEQUENCES s ON s.SEQUENCE_NAME = c.SEQUENCE_NAME
 WHERE c.TABLE_NAME IN ('SIX_INSTRUMENTS', 'SIX_STRUCTURED', 'SIX_OPTION', 'SIX_TARGET');

-- 2. AFTER deploying: same query, CACHE_SIZE must be 1000 for all 4 rows.

-- 3. Flyway status of V2_471 (success should be 1).
--    On Oracle, Flyway creates its history table with a LOWERCASE quoted name, so the
--    table and column names must be written in double quotes (unquoted -> ORA-00942).
SELECT "version", "description", "success", "installed_on"
  FROM "flyway_schema_history"
 WHERE "version" = '2.471';

-- 4. ONLY if a previous run of V2_471 failed ("success" = 0), e.g. the first version
--    with the wrong view name: remove the failed row, then restart the application.
--    Nothing was changed in the schema by that failed run.
-- DELETE FROM "flyway_schema_history" WHERE "version" = '2.471' AND "success" = 0;
-- COMMIT;
