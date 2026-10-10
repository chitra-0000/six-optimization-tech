# SIX export - part 3, export phase 1, part 4 cleanup, performance

Java 8, Spring Boot 2.7. Classes not listed here: `originalpath` (production).
This folder goes together with `six-import-confidence` (import, parts 1 and 2): deploy both folders in the same
release, on **both** batch servers.

`SixFileKind` is only in this folder; it is also used by the import classes (same package).

## 1. Files

### Java

| Class | Package | Status | Feature |
|---|---|---|---|
| `SixFilterExtractExport` | importer.six.extractor | modified | Filter step: start step, list by list, one list fails -> continue; `GENERATION_REASON`; a regeneration waits for the automatic export of the same list + version; an automatic export removes the poller rows of older raw versions |
| `SixFileFilterService` | importer.six.extractor | modified | One filter engine; CMIC / E014071 computed once; filter reads with their own fetch size (perf B) |
| `DynamicSqlBuilder` | importer.six.extractor | modified | Field whitelist, multi-regime IN list, safe `TO_NUMBER`, regime fixes |
| `InstrumentRecordTypeBuilder` | importer.six.extractor | modified | Record start dates from `SixRecordStartDates` (perf E) |
| `StructuredRecordTypeBuilder` | importer.six.extractor | modified | Same (perf E) |
| `SixRecordStartDates` | importer.six.extractor | **new** | Start dates of all records of a list's last version in one query (perf E) |
| `SixXmlGenerationPoller` | scheduler | modified | One list per claim, one server per list; files held and released together per delivery; 100 % at the release; keep-alive timer during the build (perf A) |
| `SixCleanupPoller` | scheduler | **new** | Nightly cleanup trigger (one server, retry until 02:45 if no alive batch server) |
| `SixXmlGenerationService` | service.six | modified | Holding folder, release into DJ IN, recovery of left-over files; XML streamed to the file |
| `ExportRepositoryImpl` | six.export | modified | `writeFile` (same bytes as `createFile`, streamed); `JAXBContext` created once |
| `SixFilteredStore` | importer.six.service | **new** | JDBC of `SIX_FILTERED_POLLER` / `FILTERED_SIX_*`: claim, release, statuses, chunked deletes |
| `SixFilteredRowWriter` | importer.six.service | **new** | JDBC batch writes of the filtered rows |
| `SixExportRunGuard` | importer.six.service | **new** | Failure signals between the 2 servers; job liveness; delivery key |
| `SixExportException` | importer.six.service | **new** | Technical text for the log, short text for the mail |
| `SixExportProgress` | importer.six.service | **new** | Progress plan, keep-alive during filtering |
| `SixKeepAliveTimer` | importer.six.service | **new** | Keep-alive timer used during the XML build (perf A) |
| `SixCleanupService` | importer.six.service | **new** | Nightly cleanup: export part and import part, independent |
| `SixCleanupStore` | importer.six.service | **new** | JDBC of the cleanup (TRUNCATE / chunked DELETE) |
| `SixFileKind` | importer.six.service | modified (part 1 file) | Per file type: import and export names (one place for INSTR / STRUCT / OPT) |
| `SixFilteredPoller` | entity | modified | `status`, `updatedTime`, `generationReason` |
| `SixFilteredPollerRepository` | repository.six | modified | `findByStatus`, `findByStatusAndInsertionTimeGreaterThanEqual`; `findBySixListReference` / `deleteEntriesBySixListReference` removed |
| `FilteredInstrumentFileRepository` | repository.six | modified | `findLatestVersionIdByListRef`, `findByVersionAndListRefWithTargets` (+ `findLatestByListRefWithTargets`) |
| `FilteredStructureFileRepository` | repository.six | modified | Same |
| `VersionRepository` | repository | modified | `getLatestVersionIdOfInstruments / OfStructure / OfOptions` |
| `BatchExportRepository` | repository.batch | modified | `countSixConfidenceEntries`, `countSixExportsNotStarted` |
| `BatchJobExecutionRepository` | repository.batch | modified | `findByJobTypeInAndEndDateIsNull` |
| `ReglissListFacade_partial` | facade | 1 method | `getSixTablesFieldNames()`: each filter type once, unique trimmed names |

The `TODO` line at the top of each file lists the project imports to re-add in the IDE.
No longer called (can be deleted after validation): the 3 `Filtered*ImportService`, `BatchPersistWorker.persistBatchFiltered`,
`getByListRef` / `deleteByListRef` / `delete*Batch` of the filtered repositories.

### Database (`db/`)

| Script | Content |
|---|---|
| `V2_464` / `U2_464` | `SIX_FIELD_TYPE_FILTER` (created if missing, 3 rows only if empty); undo also drops its sequence |
| `V2_465` / `U2_465` | `SIX_FILTERED_POLLER` with `STATUS`, `UPDATED_TIME`, `GENERATION_REASON`; index `IDX_SFP_STATUS_LIST (STATUS, SIX_LIST_REFERENCE)` |
| `V2_466` - `V2_468` | `FILTERED_SIX_INSTRUMENTS / _STRUCTURED / _OPTION`, identity `CACHE 1000` |
| `V2_469` | `FILTERED_SIX_TARGET`, 3 FK indexes, identity `CACHE 1000` |

`V2_464`, `U2_464`, `U2_465`, `V2_469` are also in `six-import-confidence/db`: the files are identical, copy one of each.
`V2_471` / `U2_471` (import identity cache) are only in `six-import-confidence/db`.

## 2. Flow

```
Import (six-import-confidence)  INSTR / STRUCT / OPT -> confidence MERGE -> SIX_FILTERED_FILE_GENERATION requests
Filter step  (each server, one raw file)   PENDING -> READY                  per list
XML step     (every minute, both servers)  READY -> BUILDING -> BUILT        file written to the holding folder
Release      (same run)                    BUILT -> DONE                     all files of a delivery moved into DJ IN together
DJ import    (production, unchanged)       one BATCH_IMPORT_AUTO job per CONVERTER file
```

- **Statuses of `SIX_FILTERED_POLLER`:** `PENDING`, `READY`, `BUILDING`, `BUILT`, `DONE`, `DROPPED`, plus the signals `FAILED` (one list) and `FAILED_ALL` (whole delivery).
- **Holding folder:** `<holding>/<delivery>_<REASON>/<list>/`, default `six-export-holding` next to DJ IN (same mount, seen by both servers, never inside DJ IN).
- **Release:** a delivery is released when none of its lists is still in progress. Failed lists are not waited for and not delivered (mail). One server locks the `BUILT` rows (`FOR UPDATE SKIP LOCKED`), moves the files atomically and marks them `DONE`.
- **100 % and end date** of a `BATCH_EXPORT_SIXRAW` job: when every list of the job is `DONE` (after the release). A job with a failed list stays below 100 %, no end date.
- **GENERATION / REGENERATION** (`GENERATION_REASON`, NULL = GENERATION): claimed, built and released separately. A regeneration waits while the automatic export of the same list + raw version still works on it.
- **Old poller rows:** removed when the next automatic export of the same file type starts: finished rows and rows of dead jobs of **older raw versions** of that file type. Rows of the current version, `BUILT` rows and rows of a working job are kept. A regeneration removes nothing.

## 3. Nightly cleanup (part 4)

- Cron `task.six.cleanup.cron` (default 02:00, 02:15, 02:30, 02:45). Only the batch server with the smallest alive node id runs it (`HeartbeatService.getAllActiveBatchNodeIds`). The later ticks are only used when no alive batch server was found; once run, not again that day.
- Two independent parts; a busy part is skipped until the next day (no retry), the other one still runs:

| Part | Skipped when | Cleans |
|---|---|---|
| Export | a working `BATCH_EXPORT_SIXRAW` job; an export request not yet taken; a list in progress (`BUILT`, `BUILDING` refreshed in 30 min, `PENDING` / `READY` of a working job) | 1. Holding folder: left-over CONVERTER files moved to DJ IN (unless DJ already received a newer file of that list: then removed), old `.tmp` and empty folders removed. 2. `TRUNCATE` `FILTERED_SIX_TARGET`, `FILTERED_SIX_* CASCADE`, under a lock of `SIX_FILTERED_POLLER` (chunked DELETE if TRUNCATE is refused) |
| Import | a working `BATCH_IMPORT_SIXRAW` job; a `SIX_CONFIDENCE_VALUES` row | `SIX_INSTRUMENTS` / `SIX_STRUCTURED` / `SIX_OPTION`: chunked DELETE of versions older than the latest one and than any version still used by an export in progress (`SIX_TARGET` by cascade) |

- Never touched: `VERSION`, `CTR_BATCH_JOB_EXECUTION`, `IMPORTED_FILE`, `CTR_BATCH_EXPORT`.
- "Working job" = not finished, updated in the last 30 min, server heartbeat alive. A job of a stopped server never closed does not block (warning).

## 4. Performance (measured on the 09-10 INT run: 92 581 INSTR, 38 207 STRUCT, 4 lists)

| Point | Change | Before |
|---|---|---|
| A | Keep-alive timer during the XML build: every 60 s `LAST_UPDATE_DATE` of the jobs (`incrementExportBatchForSix(job, 0)`) and `UPDATED_TIME` of the `BUILDING` rows; stopped at the end of the build; at most 120 min | No refresh during a 15-25 min build (KO risk after 30 min) |
| B | Filter reads with fetch size 500 (own template, same DataSource); writes unchanged | Oracle default 10 rows per round trip: 42 s STRUCT / 115 s INSTR read per list |
| E | One query per list for the record start dates, then in-memory lookups (same result: 60 000 random lookups identical) | One `findByExternalReferenceAndVersionId` per record (~49 000 per list): 15-25 min per list, also in the original code |

Earlier changes: JDBC batch writes of the filtered rows, XML read with one query per table (no N+1), XML streamed to the file,
`JAXBContext` created once, persistence context cleared after each list, filtering and XML build overlap.

## 5. Properties

All in `application-reglissBatch-export.properties` (this folder); all optional except the Hikari typo fix.
Existing keys used unchanged: `task.batch.export.generation`, `allow.six.file.integration`, `automatic.import.IN.directory`,
`automatic.import.xsd.CUSTOM_AUTOMATIC`, `six.cmic.list`, `six.e014071.list`, `six.exclusion.list`.

## 6. Deploy

1. **Database first.** New environment: V2_464 - V2_469 create everything. Environment where the scripts already ran: delete the
   checksum rows of the changed scripts in `flyway_schema_history` and relaunch (the scripts are re-runnable). If
   `SIX_FILTERED_POLLER` already exists without `STATUS` / `UPDATED_TIME` / `GENERATION_REASON`, run `U2_465` first (the table only
   holds export states) or add the columns:
   `ALTER TABLE SIX_FILTERED_POLLER ADD (STATUS VARCHAR2(20 CHAR), UPDATED_TIME TIMESTAMP(6), GENERATION_REASON VARCHAR2(20 CHAR));`
2. **Java:** both folders, both batch servers in the same release (an old server would put files straight into DJ IN).
3. **Properties:** add `application-reglissBatch-export.properties` (and the import one from `six-import-confidence`).
4. **Before the first run:** no SIX delivery half done (`SELECT * FROM CTR_BATCH_EXPORT WHERE BATCH_TYPE = 'SIX_CONFIDENCE_VALUES'` -> 0 rows), no SIX list locked; both batch servers listed by `HeartbeatService.getAllActiveBatchNodeIds()`.
5. `TRUNCATE ... CASCADE` needs Oracle 12c+ and the application user owning the tables; otherwise the cleanup uses DELETE automatically.
6. Optional JVM option for evidence: `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=<folder with space>`.

## 7. Check in the lower environment

- XML step per list: from 15-25 min to about 1-2 min; log `SIX XML step: start dates of N records of version V read in X ms`.
- Filter read per list: compare `filter: ... rows read ... in N ms` with the 09-10 run; log `SIX filter reads: fetch size 500`.
- The 4 CONVERTER files are equal to the previous run except the generation date; DJ import delta stays small.
- With filters configured: each list's `include sql` contains its filter conditions, rows kept differ per list.
- A build of more than 30 min (production volume): jobs stay "in progress" in the UI, the other server does not drop the list.
- Nightly cleanup: `SIX cleanup finished: ...` or `... part skipped today: <reason>`.

## 8. Reading the state

```sql
SELECT SIX_LIST_REFERENCE, FILE_TYPE, STATUS, GENERATION_REASON, RAW_VERSION_ID, INSERTION_TIME, UPDATED_TIME, BATCH_JOB_EXECUTION_ID
  FROM SIX_FILTERED_POLLER ORDER BY INSERTION_TIME DESC, SIX_LIST_REFERENCE, FILE_TYPE;
```
Main log lines: `held until every list of ...`, `... CONVERTER file(s) placed in DJ IN together`, `SIX export job N: all K lists have
their file in DJ IN - 100 %`, `SIX cleanup ...`.
