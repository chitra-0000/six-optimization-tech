# SIX import (part 1) – final package

Scope: integration of the SIX raw files into SIX_INSTRUMENTS / SIX_STRUCTURED /
SIX_OPTION (+ SIX_TARGET). Confidence update (part 2) and filtering/export (part 3)
are NOT in this package.

## Shared production classes – NOT changed
`BackpressureExecutor`, `AutomaticImporter`, `BatchPersistWorker`, the pollers, the
entities, the XSLTs and the filtered imports are not in this package and are not
changed. No global Spring setting (scheduler, connection pool) is changed.

## Contents

### java/ (package `com.bnpp.regliss.importer.six.service`)
| File | Status | What / why |
|---|---|---|
| `AutomaticSixImportXmlService` | changed | XSLT output to a temp file instead of a String; compiled XSLT cached; real error kept; partial rows of a failed import removed; timing logs. Public method unchanged. |
| `InstrumentFileImportService` | changed | Streams records and writes them with JDBC batches. Old `String` method kept for compatibility. |
| `StructuredFileImportService` | changed | Same as above. |
| `OptionsFileImportService` | changed | Same as above. Record element fixed to `OptionsFile` (old code looked for `INSTRUMENT_FILE`, so options imported 0 rows). |
| `SixBatchImportRunner` | new | Shared loop: read one record at a time → map → batches of `six.db.batch` on `automatic.import.db.thread.pool.size` threads. A failed batch now fails the import. |
| `SixJdbcBulkWriter` | new | JDBC batch INSERTs (1 round trip per 1000 rows) for parents + SIX_TARGET children, ids from each table's own identity sequence, one transaction per batch. |
| `SixXmlRecordStream` | new | StAX reader binding one record at a time into the existing DTOs. |
| `SixImportExecutor` | new | SIX-only worker pool (same design as BackpressureExecutor). Waits until all writing has really finished; always reports worker errors. |

Re-add project imports in the IDE (Alt+Enter / Optimize Imports) where marked `TODO`.

### db/
| File | Where it goes | When |
|---|---|---|
| `flyway/V2_471__six_import_identity_cache.sql` | your Flyway path (`db/migration/V2/V2_471/`) | Runs **once per database**, automatically, at the first deployment. Use the next free version number if 471 is taken. |
| `rollback/ROLLBACK_V2_471__six_import_identity_cache.sql` | release package for the DBA – **not** in the Flyway path | Only if V2_471 must be undone. |
| `checks/check_identity_cache.sql` | DBA / tester | Before and after deployment. |

`V2_471` and the rollback differ only in the cache value: **1000** (new) vs **20** (Oracle default the tables had before).

### config/
`application-reglissBatch-import.properties` – add to the reglissBatch profile. Only `six.*` keys.

## Deployment steps (each environment)
1. Run check query 1 → 4 rows expected; note `CACHE_SIZE` (expected 20).
2. If an earlier failed run of V2_471 exists (check query 3, `SUCCESS = 0`): delete that row (check query 4).
3. Deploy. Flyway applies V2_471 once.
4. Run check query 1 again → `CACHE_SIZE = 1000` for the 4 tables.

## How to verify the import
Log lines per file:
- `SIX import: InstrumentFile -> SIX_INSTRUMENTS (57 columns)` (first batch: column mapping)
- `SIX import: SIX_INSTRUMENTS ids come from "OWNER"."ISEQ$$_nnn"`
- `SIX <type> XSLT done in Ns (N MB)`
- `[instrument] DB write finished in Ns: X persisted, 0 failed (of X)`
- `SIX <type> import finished in Ns`

Compare row counts of SIX_INSTRUMENTS / SIX_STRUCTURED and SIX_TARGET for the same
input file with the current code.

## Still to confirm
- `BatchPersistWorker.persistBatchRaw` and `AbstractSimpleEntity`: if either sets a value
  beyond the mapped entity fields (or uses `@PrePersist`), the JDBC writer must do the same.
- Options: if an environment enables options (`allow.six.file.integration`), they will now
  really be imported.
