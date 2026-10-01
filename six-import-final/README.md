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
| `SixImportExecutor` | new | SIX-only worker pool (same design as BackpressureExecutor). Waits until all writing has really finished; always reports worker errors; logs a WARN if no batch finishes for `six.import.slow-warning-minutes` (20) but keeps running - a slow import is never stopped or reverted. |

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

## Confirmed from your code (repo update of 01-10)
- `BatchPersistWorker.persistBatchRaw` only sets version + list and calls `persist()` -> the JDBC writer does the same.
- `AbstractSimpleEntity` has `@EntityListeners(TruncateStringFieldsListener.class)` -> the JDBC writer calls the same
  `EntityUtils.truncateStringFields(...)` for every parent and SIX_TARGET row, so over-long text is cut exactly as today.
- 30-minute stuck check reads `BatchJobExecution.lastUpdateDate`, updated by `incrementPercent` after every batch.
- Server pickup: per-list DB lock (`attemptLockList`, CTR_LIST_IMPORT_NODE_LOCK) - unchanged, works for 1 or 2 servers.
- Identity cache = 1000 on the 4 tables in REGLISSDEV4 (V2_471 applied).

## Measured with your XSLT on a 305 MB instrument file (built from your sample)
| Step | Result |
|---|---|
| XSLT (unchanged) | 55 s, ~2.4 GB heap, 797 MB temp output |
| Read output record by record | 3 s |
| Rows to insert | 142,352 SIX_INSTRUMENTS + 782,936 SIX_TARGET |

The temp output (~2.7x the input) is written to `java.io.tmpdir` (/applis/11672-regli/tmp) and deleted after the
import: keep ~2 GB free there (2 files at the same time on one server).

## Still to confirm
- Options: if an environment enables options (`allow.six.file.integration`), they will now really be imported.
