# SIX part 4 - cleanup + holding-folder recovery

Built on `updatedpath` (latest `da36126`) + `originalpath` (production classes). Java 8, Spring Boot 2.7.
No new table, no new column, no Flyway script. `BatchJobExecution` is unchanged (no status field).

## Files

| Folder | File | Change |
|---|---|---|
| six-part4-cleanup/java | `SixCleanupPoller` (scheduler) | **New.** Cron `task.six.cleanup.cron`; only one server runs it (see below) |
| six-part4-cleanup/java | `SixCleanupService` (importer.six.service) | **New.** Two independent parts (export / import), each with its own check |
| six-part4-cleanup/java | `SixCleanupStore` (importer.six.service) | **New.** JDBC: TRUNCATE / DELETE statements |
| six-part4-cleanup/java | `VersionRepository` (prod) | + `getLatestVersionIdOfInstruments / OfStructure / OfOptions` (same rule as `getLatestVersionOfInstruments`) |
| six-part4-cleanup/java | `BatchExportRepository` (prod) | + `countSixConfidenceEntries`, `countSixExportsNotStarted` |
| six-part4-cleanup/java | `BatchJobExecutionRepository` (prod) | + `findByJobTypeInAndEndDateIsNull` (derived query, like the existing `findByNodeIdAndJobTypeInAndEndDateIsNull`) |
| six-part3-final/java | `SixFilterExtractExport` | At the start of an **automatic** export: removes the `SIX_FILTERED_POLLER` rows of older raw versions of its file type (see below) |
| six-part3-final/java | `SixFilteredStore` | + `deleteOlderVersionRows` |
| six-part3-final/java | `SixFilteredPollerRepository` | `findBySixListReference` and `deleteEntriesBySixListReference` **removed** (they read / deleted every row of a list whatever its raw version; only the old production `SixXmlGenerationPoller` used them) |
| six-part3-final/java | `SixXmlGenerationService` | + `heldFiles`, `deliverHeldFile`, `discardHeldFile`, `removeTempFilesAndEmptyFolders`, `holdingFolderName`, class `HeldFile`. XSD validation and XML unchanged |
| six-part3-final/java + db | `SixXmlGenerationPoller`, `SixExportRunGuard`, `FilteredInstrumentFileRepository`, `FilteredStructureFileRepository`, `V2_465` | **Same as delta2** (changes A-E), not yet on the branch. `SixExportRunGuard`: one comment line changed |

## SIX_FILTERED_POLLER: cleaned by the automatic export itself

When an automatic export (GENERATION) of one raw file starts, before its `PENDING` rows are written, it deletes the rows of **its own file type** whose **raw version is older** than the one it exports:

| Row of an older raw version | Result |
|---|---|
| `DONE`, `DROPPED`, `FAILED`, `FAILED_ALL` | Deleted |
| `PENDING`, `READY`, `BUILDING` of a job that no longer works (and `BUILDING` not refreshed for 30 min) | Deleted |
| `PENDING`, `READY` of a working job, `BUILDING` refreshed in the last 30 min | Kept |
| `BUILT` (a file waits in the holding folder) | Kept (the nightly recovery moves the file) |

- **Every row of the current (or a newer) raw version is kept**, whatever its status (including `FAILED`, which the running export uses to stop failed lists).
- **Only its own file type:** the INSTR and STRUCT exports of one delivery run at the same time, often on the two servers, with different raw versions.
- **A regeneration does not clean.**
- No 8-hour window. An error here is logged and the export continues.
- The nightly cleanup no longer empties `SIX_FILTERED_POLLER`.

## Nightly cleanup: when it runs

- Once a day, `task.six.cleanup.cron` (default `0 0/15 2 * * *`: ticks at 02:00, 02:15, 02:30, 02:45). Both servers fire; only the batch server with the **smallest node id among the alive ones** (`HeartbeatService.getAllActiveBatchNodeIds`) runs it. If that server is down, the other one does.
- **No alive batch server found** (or the heartbeat table cannot be read): tried again at the next tick, every 15 minutes, last try 02:45. After that, the next day.
- **Once a server has run the cleanup** (both parts done or skipped), its later ticks that day do nothing. A skipped part is never retried that day.
- Kept in memory per server: if the server that ran at 02:00 dies before 02:45, the other server may run it once more that night. This is harmless: every step can safely run again.

## Two independent parts (same scheduler, same service)

If one part is busy, only that part is skipped (reason logged); the other one still runs. "Working job" = not finished, updated in the last 30 min, server heartbeat alive (same rule as `SixExportRunGuard.isJobWorking`). A job of a stopped server that was never closed does not block (a warning is logged).

| Part | Skipped when | Cleans |
|---|---|---|
| **Export** | a working `BATCH_EXPORT_SIXRAW` job; a `SIX_FILTERED_FILE_GENERATION` request not yet taken by a server; a list in progress in `SIX_FILTERED_POLLER` (`BUILT`, `BUILDING` refreshed in the last 30 min, `PENDING` / `READY` of a working job) | 1. Holding folder recovery. 2. `TRUNCATE FILTERED_SIX_TARGET`, `TRUNCATE FILTERED_SIX_INSTRUMENTS / _STRUCTURED / _OPTION CASCADE`, under a lock of `SIX_FILTERED_POLLER` |
| **Import** | a working `BATCH_IMPORT_SIXRAW` job; a `SIX_CONFIDENCE_VALUES` row | Chunked `DELETE` of old versions in `SIX_INSTRUMENTS`, `SIX_STRUCTURED`, `SIX_OPTION` (`SIX_TARGET` by cascade) |

- **Raw versions kept** (per table): the latest one (`MAX(VERSION.ID)` of that file type, what a regeneration reads), and every version still used by an export list in progress of that file type. The delete is `VERSION_ID <` the lowest kept version. An export reads the other raw tables at their latest version only, so a running export never loses its data.
  - Example: a regeneration works on INSTR 101 and INSTR 102 was imported meanwhile: INSTR 101 and 102 are kept, older ones deleted.
- **A latest version with no row** (a failed import whose rollback did not finish) leaves that table as it is (warning).
- **Why the lock:** an export writes its `PENDING` rows to `SIX_FILTERED_POLLER` before any filtered row. An export that starts during the TRUNCATEs waits (a few seconds) and cannot lose filtered rows. The idle check is repeated under the lock.
- **Why `VERSION_ID <`:** a SIX import that starts meanwhile has a higher version id, so its rows are never touched. The delete also stops at the next chunk when an import starts.
- **If a TRUNCATE is refused** (rights, Oracle older than 12c), the table is emptied by chunked DELETEs instead.
- **Errors:** an error stops its part only, and is logged; the next night continues. A lost DB connection is not retried.
- **Never touched:** `VERSION`, `CTR_BATCH_JOB_EXECUTION`, `IMPORTED_FILE`, `CTR_BATCH_EXPORT`.

### Holding folder: which left-over file is delivered

For each list, only the **newest** left-over file can be delivered. It is moved to DJ IN unless a `DONE` row of that list shows that DJ already received:
- a later delivery (date+time of the SIX files), or
- the same delivery, released after this file was built (e.g. a regeneration).

All other left-over files of the list are removed. A file that cannot be moved stays in the holding folder and is tried again the next night.

| Case | Result |
|---|---|
| Release of the delivery failed (move error, list `DROPPED`) | Delivered at night |
| Server crashed after the file was built and validated, before `BUILT` was recorded | Delivered at night |
| Left-over file of an older delivery, DJ already has the newer one | Removed |
| Automatic file left over, a regeneration of the same delivery delivered later | Removed |
| Regeneration file built after the automatic release of the same delivery | Delivered |

Files whose row is still `BUILT` (both servers stopped, then restarted) are released by the XML step itself after the restart, at most 30 minutes after the stopped jobs are seen as dead.

## Properties

```properties
# ticks every 15 min from 02:00 to 02:45: the later ticks only run when no alive batch server was found before
task.six.cleanup.cron=0 0/15 2 * * *
# optional: rows per DELETE chunk (raw tables, and filtered tables when TRUNCATE is refused)
six.cleanup.delete.chunk.size=2000
```

## Deploy notes

- No DB script. `TRUNCATE ... CASCADE` needs Oracle 12c or later and the application user owning the tables; otherwise the DELETE fallback is used automatically.
- The first run deletes every old raw version at once. Run it once in the lower environment and check the duration and the undo.
- Spring's scheduler runs `@Scheduled` methods on its own pool (one thread unless configured otherwise). While the cleanup runs on a server, that server's other pollers wait. The other server keeps working.
- Check that both batch servers appear in `HeartbeatService.getAllActiveBatchNodeIds()` (batch profile). If none appears at any tick until 02:45, the cleanup does not run that day: log line `SIX cleanup: no alive batch server found (heartbeat), tried again at the next tick`.
- If any other class of your project calls `SixFilteredPollerRepository.findBySixListReference` or `deleteEntriesBySixListReference`, it no longer compiles: tell me and I will replace the call.

## Tests (harness: real classes, SQLite for Oracle, real folders)

All 73 cleanup checks pass. The 65 export-release checks still pass.

1. Independent parts: working import job or confidence row -> only the import part skipped; working export job, export request not started, `BUILT` row, fresh `BUILDING`, `PENDING`/`READY` of a working export job -> only the export part skipped.
2. Idle, with dead unfinished jobs: filtered tables truncated (parents with CASCADE), lock taken first, poller not emptied. Raw tables keep only the latest version (dead rows do not protect a version); `SIX_TARGET` rows removed by cascade. No statement on `VERSION` or the job, file or export tables.
3. Raw tables: a newer version than the latest is kept; latest without rows -> table untouched; no version -> untouched; a regeneration still working on INSTR 101 -> 101 and 102 kept, 100 deleted, export part skipped.
4. Holding folder recovery, the five cases above, plus `.tmp` and empty folders. The second run moves nothing.
5. DJ IN unreachable: the file is kept, the rest is cleaned; the next night the file is delivered.
6. Errors: TRUNCATE refused -> chunked DELETE; lock busy -> export part stops, import part still runs; DB error in the import part -> export part still done.
7. A job starts during the cleanup: the raw delete stops; an export that started before the lock is seen under the lock, nothing truncated.
8. Automatic export start, 15 poller rows: old `DONE` / `DROPPED` / `FAILED` / `FAILED_ALL` and dead `PENDING` / `READY` / `BUILDING` deleted; `BUILT`, rows of a working job, fresh `BUILDING`, every row of the current or a newer version, and every STRUCT row kept. A regeneration removes nothing. A DB error does not stop the export.
9. Node election: the smallest alive node runs it; the other takes over when it is dead.
10. Retry of the election until 02:45; once run, not again that day; a skipped part is not retried.

Run in the lower environment: an automatic export after a previous one (check the old rows go), then the cleanup with an export running (scenario 3).

## Log lines

`SIX cleanup: export part skipped today: ...`, `SIX cleanup: import part skipped today: ...`, `SIX cleanup finished: ...`, `... SIX_FILTERED_POLLER row(s) of older ... versions removed`, `versions from ... kept, still used by a SIX export`, `left-over CONVERTER file ... moved to DJ IN`
