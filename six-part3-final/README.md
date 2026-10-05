# SIX export (part 3) - agreed flow

Built on top of the `originalpath` branch (export classes) and the part 1 / part 2 code of `updatedpath`
(`six-import-confidence`: `SixFileKind`, `SixJdbcBulkWriter`, `SixImportExecutor`, `SixDeliveryService`).
Only new and modified files are in this zip.
Scripts: the versions of the `updatedpath` branch (`six-import-confidence/db`), plus V2_466 - V2_468 (not on the branch yet).
No new Flyway script: the changes are in the existing creation scripts.

## 1. Files

### Scripts (`db/`)

| File | Status | What changed |
|---|---|---|
| `V2_464__add_table_six_field_type_filter.sql` | **modified** (= updatedpath) | Creates table + sequence if missing; inserts the 3 rows only when the table is empty |
| `U2_464__add_table_six_field_type_filter.sql` | **modified** (= updatedpath) | Bug fix: drops `SIX_FIELD_TYPE_FILTER_SEQ` (was never dropped) |
| `V2_465__add_six_filtered_poller.sql` | **modified** (= updatedpath) | `STATUS VARCHAR2(20 CHAR)` and `UPDATED_TIME TIMESTAMP(6)` in the CREATE TABLE |
| `U2_465__add_six_filtered_poller.sql` | **modified** (= updatedpath) | Bug fix: drops `SIX_FILTERED_POLLER` + `SIX_FILTERED_POLLER_SEQ` (it pointed to `SIX_FIELD_TYPE_FILTER`) |
| `V2_466__add_filtered_six_instruments.sql` | **modified** (not on updatedpath yet) | Last line: identity `CACHE 1000` on `FILTERED_SIX_INSTRUMENTS` |
| `V2_467__add_filtered_six_structured.sql` | **modified** (not on updatedpath yet) | Last line: identity `CACHE 1000` on `FILTERED_SIX_STRUCTURED` |
| `V2_468__add_filtered_six_option.sql` | **modified** (not on updatedpath yet) | Last line: identity `CACHE 1000` on `FILTERED_SIX_OPTION` |
| `V2_469__filtered_six_target.sql` | **modified** (= updatedpath) | 3 FK indexes `IDX_FT_SIX_INSTR_ID` / `IDX_FT_SIX_STRUCT_ID` / `IDX_FT_SIX_OPT_ID` (created if missing) + identity `CACHE 1000` |

No undo needed for the `CACHE 1000` lines: the undo of a table drops it with its setting; leaving 1000 is harmless (only gaps in IDs after a restart).
Removed compared with the previous zip: `V2_472` / `U2_472` (moved into V2_466 - V2_469).

### Java

| File | Package | Status | What changed |
|---|---|---|---|
| `SixFilterExtractExport` | importer.six.extractor | **modified** | Agreed flow: start step, list by list, one list fails -> continue, stop-all errors |
| `SixXmlGenerationPoller` | scheduler | **modified** | One list per claim, one server per list, a failed file affects only its list, progress, 100 % at the end |
| `SixFileFilterService` | importer.six.extractor | **modified** | One engine instead of 3 copies; CMIC / E014071 computed once at the start; HashSets |
| `DynamicSqlBuilder` | importer.six.extractor | **modified** | Field whitelist, multi-regime IN list, safe `TO_NUMBER`, regime fixes, unsupported operator is an error |
| `SixXmlGenerationService` | service.six | **modified** | `createAndUploadFileOrThrow` (real cause instead of "KO"); temp file in DJ IN is `.tmp` and always deleted |
| `SixFilteredPoller` | entity | **modified** | New `status` (`STATUS`) and `updatedTime` (`UPDATED_TIME`) fields, and a constructor with the status |
| `SixFilteredPollerRepository` | repository.six | **modified** | Added `findByStatus` |
| `FilteredInstrumentFileRepository` | repository.six | **modified** | Added `findLatestByListRefWithTargets` (one query, no N+1) |
| `FilteredStructureFileRepository` | repository.six | **modified** | Added `findLatestByListRefWithTargets` |
| `SixFileKind` | importer.six.service | **modified** (part 1 file) | Export fields (filter type, root ISIN, FK column, filtered table, hint indexes) |
| `ReglissListFacade_partial.java` | (facade) | **modified**, 1 method | `getSixTablesFieldNames()`: each filter type once, unique trimmed field names |
| `SixExportException` | importer.six.service | **new** | Technical text for the log, short business text for the mail |
| `SixExportRunGuard` | importer.six.service | **new** | Failure signals shared by the 2 servers (`FAILED` per list, `FAILED_ALL` per delivery) |
| `SixFilteredStore` | importer.six.service | **new** | JDBC: chunked deletes, list states in `SIX_FILTERED_POLLER`, claim |
| `SixFilteredRowWriter` | importer.six.service | **new** | JDBC batch writes of the filtered rows (reuses `SixJdbcBulkWriter` / `SixImportExecutor`) |
| `SixExportProgress` | importer.six.service | **new** | Progress plan (equal share per list), keep-alive timer |

**Unchanged:**
- **Shared classes:** `BatchExportPoller`, `BackpressureExecutor`, `AutomaticImporter`, `BatchManagementService`, `BatchProgressService`.
- **Mapper:** `FilteredDataMapper`.
- **Schema:** two new columns, `SIX_FILTERED_POLLER.STATUS` and `SIX_FILTERED_POLLER.UPDATED_TIME`. No new table or property.

**No longer called by the export (delete after validation):**
- the 3 `Filtered*ImportService`;
- `BatchPersistWorker.persistBatchFiltered`;
- `getByListRef` / `deleteByListRef` / `delete*Batch` of the filtered repositories;
- `deleteEntriesBySixListReference`.

The `TODO` line at the top of each Java file lists the project imports to re-add.

## 2. Flow

**Example:** 2 export entries (instrument, structured), taken at random by the 2 servers. Output lists 951, 952, 225 and 235.

### Filter step (`SixFilterExtractExport`, each server, one raw file)

1. **Start (once).** Read:
   - the output lists;
   - **one snapshot of the filters of every list**;
   - the CMIC / E014071 exclusions, computed **once** and only if a list needs them.

   Then create one row per list in `SIX_FILTERED_POLLER` (`FILE_TYPE` = `INSTR`, `STATUS` = `PENDING`). Progress: 5 %.
2. **Per list (any order):**
   1. Check the failure signals: did the delivery stop, or did this list fail on the other server?
   2. Filter.
   3. Delete the previous rows of this version.
   4. Write the rows (JDBC batches).
   5. The list's row goes from `STATUS` = `PENDING` to `READY`.
3. **The XML step can build a list's file as soon as every file type of that list is ready.** Filtering of the next lists continues in parallel.

### XML step (`SixXmlGenerationPoller`, every minute, both servers)

- **Claim:** a server claims one ready list. Its rows get `STATUS` = `BUILDING` in the same transaction (`FOR UPDATE SKIP LOCKED`), so **only one server** builds a list.
- **Build:** generic rules, then `ListTypeBuilder`, then the CONVERTER file in DJ IN. The rows get `STATUS` = `DONE`.
- **Next:** the server goes back to the table for the next ready list.

### List states in `SIX_FILTERED_POLLER`

`FILE_TYPE` stays the plain file type (`INSTR`, `STRUCT`, `OPTIONS`); the state is in the new `STATUS` column. Every status change also sets the new `UPDATED_TIME` column (equal to `INSERTION_TIME` when the row is created). The rows are kept until the nightly cleanup, as an audit of every list of every job:

```
STATUS:  PENDING -> READY -> BUILDING -> DONE
            \-> DROPPED (list failed / skipped / export stopped)     \-> DROPPED (XML failed)
         FAILED     : one list failed (SIX_LIST_REFERENCE = the list)
         FAILED_ALL : the export of the delivery is stopped
```

Example query - state of every list of today's export:
```sql
SELECT SIX_LIST_REFERENCE, FILE_TYPE, STATUS, INSERTION_TIME, UPDATED_TIME, BATCH_JOB_EXECUTION_ID
  FROM SIX_FILTERED_POLLER ORDER BY SIX_LIST_REFERENCE, FILE_TYPE;
```

For the cleanup (Part 4), example: rows not changed for more than one day:
```sql
SELECT * FROM SIX_FILTERED_POLLER WHERE UPDATED_TIME < SYSTIMESTAMP - INTERVAL '1' DAY;
```

## 3. Errors (negative scenarios)

| Error | What happens |
|---|---|
| **One list fails while filtering or writing** (e.g. 225 on the instrument server) | Partial rows of 225 removed; `FAILED` for 225; one mail. **The server continues with the next lists.** The other server **skips** 225, or, if it already wrote 225, the XML step **drops** it. No half file. |
| **One XML file fails** (e.g. 951) | Rows of 951 get `STATUS` = `DROPPED`, one mail. **The server continues with the next ready list.** No automatic retry (no mail every minute). |
| **Error at the start** (no active output list, CMIC / E014071 exclusion query, database) | `FAILED_ALL`, one mail. **Both servers stop:** the other one stops before its next list. Lists not filtered yet are dropped. |
| **Database not reachable** during a list | Treated like a start error: both servers stop, one mail. Lists that were **already complete** (both file types ready) still get their file. |
| **Server crash / restart** | Nothing resumes it. The job turns KO; regenerate manually. |

**Job status after any failure:**
- **No end date and below 100 %.** The job is kept alive only while it still has work, then turns **KO** 30 minutes after its last update.
- **Both jobs** of the delivery stay below 100 %, because the failed list is `DROPPED` for both file types.
- Complete them manually; that also unblocks the next import.

**Regeneration** is not blocked by the old failure: it only sees failures that happen after it starts.

### Log and mail

- **Server log:** the full technical detail.
  ```
  SIX export STOPPED while inserting the filtered rows into FILTERED_SIX_* - list 225, INSTRUMENT file version 101,
  delivery 20261004101500, job 1. Cause: SQLException: ORA-01653: unable to extend table   (+ stack trace)
  ```
- **Mail:** short, business level.
  ```
  Error occurred during writing the filtered data of list 225 (instrument file) due to database error.
  The other lists continue. Please regenerate this list. Technical details: server log of job 1.
  ```

**Mail steps:**
- preparation of the export;
- filtering;
- writing the filtered data;
- generating the XML file.

**Mail reasons:**
- database not reachable;
- database error;
- invalid filter definition;
- invalid data in the SIX file;
- duplicated external reference in the filtered data;
- XML file not valid against the schema;
- file could not be written to the DJ IN folder;
- no active output list;
- unexpected technical error.

## 4. Progress

The same plan applies for 1 or 2 servers, the normal export and a regeneration (1 list or more):

| Part of one job (one raw file, N lists) | Share |
|---|---|
| Start | 5 % |
| Each list: 90 % / N | filter 30 %, delete previous rows 5 %, write 45 % (moves with every batch), XML file 20 % |
| **Automatic maximum** | **95 %** |
| 100 % + end date | Only by the existing `BatchManagementService.updateBatchExecutionCompletedProgress` + `markBatchExecutionFinishedBySys`, when **every** list of the job has its file |

**Examples:**

| Case | Progress |
|---|---|
| 4 lists, all OK | Filter step ends at 77 %; XML files take it to 95 %; then the service sets 100 % |
| Regeneration of 1 list | 5 % → 77 % → 95 % → 100 % |
| 225 fails | Both jobs stop around 70–90 %, no end date, then KO |

**Keep-alive:**
- **During filtering:** a timer refreshes the last update date every minute, also during one long SQL query.
- **While a list waits for the other file type:** its job is refreshed only while the job it waits for is alive. If that server crashed, both jobs turn KO as expected.

## 5. Filters (unchanged since the last delivery)

- **One main query per list.**
- **CMIC / E014071 exclusions:** computed once per raw file and reused by every list.
- **"All targets" rules:** applied as before.
- **Fixes:**
  - multi-regime IN list;
  - safe `TO_NUMBER`;
  - regime always compared as text;
  - `contains` / `starts with` always compared as text;
  - Data extractor "ISIN" mapped to the ISIN column of each table;
  - empty filter skipped;
  - field whitelist (Fortify).
- **Performance:**
  - FK indexes on `FILTERED_SIX_TARGET` (V2_469) and identity `CACHE 1000` (V2_466 - V2_469);
  - JDBC batch writes;
  - XML read with one query per table;
  - O(n) sanction rule (same result).

## 6. Tests run here

There is no Oracle here: stub Spring classes, plus SQLite with Oracle behaviour emulated.

1. **Filters, original against new:**
   - same rows wherever the original ran (1,200 random cases with every operator and combination);
   - the new code also runs in the cases where the original failed (ORA-01722, ORA-00904, empty clause).
2. **Sanction rule:** identical in 20,000 of 20,000 random cases.
3. **Two-server flow, in-memory `SIX_FILTERED_POLLER`:**

| Scenario | Result |
|---|---|
| All OK | 951 file built while 952..235 still filtered; 4 files; both jobs 100 % + end date only at the end; structured job at 77 % after filtering |
| 225 fails while filtering (instrument) | Instrument continues with 951, 235; structured skips 225; 3 files; 1 short mail; both jobs below 100 %, no end date |
| 225 fails while writing, structured already wrote 225 | Structured 225 dropped by the XML step; partial rows removed; 3 files; 1 mail |
| XML of 951 fails | 3 other files; 1 mail; no retry next minute; both jobs below 100 % |
| Start error (exclusion query) | Both servers stop before any list; 1 mail; no file; no PENDING row left |
| Database not reachable on 225 | Both stop; 951, 952 (already complete) still built; 225, 235 not |
| Regeneration of 225 afterwards | File built; regeneration jobs 100 % |
| Keep-alive | Waiting job refreshed while the other job is alive, not after it crashed |

4. **XML service:** an invalid XML gives the real cause and leaves nothing in DJ IN.

**Please check in a lower environment:**
- the JPQL of the 2 new repository methods;
- `BatchJobExecution.getStartDate()`, `getLastUpdateDate()`, `getPercent()` and `getJobParams()`;
- Oracle 12.2 or higher (`SELECT banner FROM v$version;`) for `DEFAULT NULL ON CONVERSION ERROR`.

## 7. Deploy

- **New environment:** V2_464 - V2_469 create everything (tables, new columns, indexes, cache). Nothing else to do.
- **Your environment (scripts already ran):** delete the checksum rows of the changed scripts in `flyway_schema_history` and relaunch.
  - V2_466 - V2_469 re-run safely: the CREATE is skipped (table exists), the indexes are created only if missing, the `ALTER ... CACHE 1000` always runs.
  - **V2_465:** the CREATE is skipped when `SIX_FILTERED_POLLER` exists, so `STATUS` / `UPDATED_TIME` are **not** added. Before relaunching, run `U2_465` (drops the table; it only holds export states, nothing to keep) or add the 2 columns by hand:
    ```sql
    ALTER TABLE SIX_FILTERED_POLLER ADD (STATUS VARCHAR2(20 CHAR), UPDATED_TIME TIMESTAMP(6));
    ```
- **Order:** deploy the scripts with or before the Java (the new code reads `STATUS` / `UPDATED_TIME`).
- Rows written by the old code have no `STATUS`; the new code ignores them.
