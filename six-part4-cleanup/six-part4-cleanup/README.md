# SIX part 4 - nightly cleanup + holding-folder recovery

Built on `updatedpath` (latest `da36126`) + `originalpath` (production classes). Java 8, Spring Boot 2.7.
No new table, no new column, no Flyway script. `BatchJobExecution` is unchanged (no status field).

## Files

| Folder | File | Change |
|---|---|---|
| six-part4-cleanup/java | `SixCleanupPoller` (scheduler) | **New.** Cron `task.six.cleanup.cron`; only one server runs it (see below) |
| six-part4-cleanup/java | `SixCleanupService` (importer.six.service) | **New.** Idle check, holding-folder recovery, table cleanup |
| six-part4-cleanup/java | `SixCleanupStore` (importer.six.service) | **New.** JDBC: TRUNCATE / DELETE statements |
| six-part4-cleanup/java | `VersionRepository` (prod) | + `getLatestVersionIdOfInstruments / OfStructure / OfOptions` (same rule as `getLatestVersionOfInstruments`) |
| six-part4-cleanup/java | `BatchExportRepository` (prod) | + `countSixConfidenceEntries`, `countSixExportsNotStarted` |
| six-part4-cleanup/java | `BatchJobExecutionRepository` (prod) | + `findByJobTypeInAndEndDateIsNull` (derived query, like the existing `findByNodeIdAndJobTypeInAndEndDateIsNull`) |
| six-part3-final/java | `SixXmlGenerationService` | + `heldFiles`, `deliverHeldFile`, `discardHeldFile`, `removeTempFilesAndEmptyFolders`, `holdingFolderName`, class `HeldFile`. Nothing else changed (XSD validation and XML unchanged) |
| six-part3-final/java + db | `SixXmlGenerationPoller`, `SixExportRunGuard`, `SixFilteredPollerRepository`, `FilteredInstrumentFileRepository`, `FilteredStructureFileRepository`, `V2_465` | **Same as delta2** (changes A-E). Not yet on the branch, so they are included again. |

## When it runs

- Once a day, `task.six.cleanup.cron` (default `0 0 2 * * *`). Both servers fire; only the batch server with the **smallest node id among the alive ones** (`HeartbeatService.getAllActiveBatchNodeIds`) runs it. If that server is down, the other one does.
- It runs only when **nothing SIX is in progress**. Otherwise it is skipped until the next day (no retry, reason logged):
  - no working `BATCH_IMPORT_SIXRAW` / `BATCH_EXPORT_SIXRAW` job (not finished, updated in the last 30 min, server heartbeat alive: same rule as `SixExportRunGuard.isJobWorking`). A job of a stopped server that was never closed does not block the cleanup (a warning is logged);
  - no `SIX_CONFIDENCE_VALUES` row in `CTR_BATCH_EXPORT`;
  - no `SIX_FILTERED_FILE_GENERATION` request that is not yet taken by a server;
  - no list in progress in `SIX_FILTERED_POLLER`: `BUILT`, `BUILDING` refreshed in the last 30 min, `PENDING` / `READY` of a working job.

## What it does (in this order; before each step it checks again that nothing has started)

| Step | Action |
|---|---|
| 1. Holding folder | Every CONVERTER file left there is **moved to DJ IN** (the same atomic move as the release). Exception: DJ already received the same or a newer file for that list (see below); then the left-over file is old data and is removed (warning). `.tmp` files older than 60 min and empty folders are removed. |
| 2. Export tables | `SIX_FILTERED_POLLER` is locked (`LOCK TABLE ... IN EXCLUSIVE MODE NOWAIT`). Idle is checked again under the lock. Then: `TRUNCATE FILTERED_SIX_TARGET`, `TRUNCATE FILTERED_SIX_INSTRUMENTS / _STRUCTURED / _OPTION CASCADE`, and `DELETE FROM SIX_FILTERED_POLLER`. If a TRUNCATE is refused (rights, Oracle older than 12c), the table is emptied by chunked DELETEs instead. |
| 3. Raw tables | `SIX_INSTRUMENTS`, `SIX_STRUCTURED`, `SIX_OPTION`: chunked `DELETE ... WHERE VERSION_ID < latest`. `SIX_TARGET` rows go with them (ON DELETE CASCADE, V2_470). Latest = `MAX(VERSION.ID)` of that file type, the version a regeneration reads. |

- **Never touched:** `VERSION`, `CTR_BATCH_JOB_EXECUTION`, `IMPORTED_FILE`, `CTR_BATCH_EXPORT`.
- **Why the lock:** an export writes its `PENDING` rows to `SIX_FILTERED_POLLER` before any filtered row. An export that starts during step 2 waits (a few seconds) and cannot lose filtered rows.
- **Why `VERSION_ID < latest`:** a SIX import that starts during step 3 has a higher version id, so its rows are never touched. The delete also stops at the next chunk.
- **A latest version with no row** (a failed import whose rollback did not finish) leaves that table as it is (warning).
- **Errors:** an error stops the run and is logged; the next night continues. A lost DB connection is not retried. Every step can safely run again.
- **Left-over file that cannot be moved:** the file stays in the holding folder, and `SIX_FILTERED_POLLER` is **kept** so the next night can decide again. The other steps still run.

### Holding folder: which left-over file is delivered

For each list, only the **newest** left-over file can be delivered. It is moved to DJ IN unless a `DONE` row of that list shows that DJ already received:
- a later delivery (date+time of the SIX files), or
- the same delivery, released after this file was built (e.g. a regeneration).

All other left-over files of the list are removed.

| Case | Result |
|---|---|
| Release of the delivery failed (move error, list `DROPPED`) | Delivered at night |
| Server crashed after the file was built and validated, before `BUILT` was recorded | Delivered at night |
| Left-over file of an older delivery, DJ already has the newer one | Removed |
| Automatic file left over, a regeneration of the same delivery delivered later | Removed |
| Regeneration file built after the automatic release of the same delivery | Delivered |

Files whose row is still `BUILT` (both servers stopped, then restarted) are not waited for until the night. The XML step releases them by itself after the restart, at most 30 minutes after the stopped jobs are seen as dead (as in phase 1).

## Properties

```properties
# once a day; default 02:00. Pick a time outside the SIX import / export slots.
task.six.cleanup.cron=0 0 2 * * *
# optional: rows per DELETE chunk (raw tables, and filtered tables when TRUNCATE is refused)
six.cleanup.delete.chunk.size=2000
```

## Deploy notes

- No DB script. `TRUNCATE ... CASCADE` needs Oracle 12c or later and the application user owning the tables; otherwise the DELETE fallback is used automatically.
- The first run deletes every old raw version at once. Run it once in the lower environment and check the duration and the undo.
- Spring's scheduler runs `@Scheduled` methods on its own pool (one thread unless configured otherwise). While the cleanup runs on a server, that server's other pollers wait. The other server keeps working.
- Check that both batch servers appear in `HeartbeatService.getAllActiveBatchNodeIds()` (batch profile). If none appears, the cleanup never runs: log line `SIX cleanup: runs on batch server (none alive)`.

## Tests (harness: real classes, SQLite for Oracle, real folders)

All 46 cleanup checks pass. The 65 export-release checks still pass with the changed `SixXmlGenerationService`.

1. Each "in progress" case leads to a skip, with nothing removed: working job, confidence row, export not yet started, `BUILT` row, fresh `BUILDING` row, `PENDING`/`READY` of a working job.
2. Idle, with a dead unfinished job: filtered tables truncated (parents with CASCADE) and targets empty, poller empty, the lock taken first. Raw tables keep only the latest version, and `SIX_TARGET` rows are removed by cascade. No statement touches `VERSION` or the job, file or export tables.
3. Raw tables:
   - a version newer than the latest is kept;
   - when the latest version has no rows, the table is untouched;
   - when there is no version, raw is untouched.
4. Holding folder recovery, the six cases above. The second run moves nothing.
5. DJ IN unreachable: the file is kept and the poller is kept, the rest is cleaned. The next night the file is delivered.
6. Errors:
   - TRUNCATE refused: the chunked DELETE runs instead;
   - the lock is busy: nothing is removed;
   - the connection is lost: the run stops.
7. A job starts during the cleanup: the raw delete stops. If the export starts before the lock, it is seen under the lock and nothing is truncated.
8. Node election: the smallest alive node runs the cleanup, and the other server takes over when it is dead.

Run in the lower environment: scenario 2, then one failed release (scenario 4, case 1).

## Log lines

`SIX cleanup skipped today: ...`, `SIX cleanup finished: ...`, `left-over CONVERTER file ... moved to DJ IN`, `left-over CONVERTER file ... removed: DJ already received ...`
