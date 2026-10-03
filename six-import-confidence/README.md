# SIX part 2: delivery "all or nothing" + confidence step

Apply on top of the `updatedpath` branch (part 1). Use `originalpath` for every class not listed here.
**No new table, no new column, no new folder, no new property.**
`BackpressureExecutor` and `AutomaticImporter` are not changed.

## Files

| File | Package | Status | What |
|---|---|---|---|
| `SixFileKind` | importer.six.service | **new** | The SIX file types in one enum (marker, table, link column). A 4th type = one line here. |
| `SixDeliveryService` | importer.six.service | **new** | State of a delivery (files of one timestamp), rollback of the whole delivery, finish of one file, keep-alive. |
| `SixConfidenceStore` | importer.six.service | **new** | The SQL: MERGE built from `SixFileKind`, exactly-once claim of a `CTR_BATCH_EXPORT` row, delivery key from `JOB_PARAMS`. |
| `SixBatchConfidencePoller` | scheduler | **replaced** | Same class name and cron (`task.batch.export.generation`); new logic (below). |
| `AutomaticFeedImporter` | scheduler | changed | Only `importSixFeedWithoutTx`: the file is no longer deleted after a successful import; the delivery is rolled back on failure. |
| `AutomaticSixImportFileRepository` | scheduler | changed | New method `moveToErrorDirectoryIfPresent` (never deletes a file already in ERROR). |
| `SixBatchImportRunner` | importer.six.service | changed (vs updatedpath) | After each batch: `keepWaitingFilesAlive` (at most once a minute). |

Re-add project imports in the IDE where marked `TODO`.

## How it works

**A delivery** = the SIX files with the same date+time in their name (same key as `checkAnySixFilesAvailableToBePrecessed`).

**After a successful import**, the file stays in IN and its list stays locked (`getAvailableFeedsForImportFormat`
already skips locked lists, so it is never imported twice). This also makes STRUCT/OPT importable after INSTR
on one server: the delivery check needs the INSTR file to still be in IN.

**If any file fails** (import or confidence MERGE), the whole delivery is rolled back, on 1 or 2 servers:
1. all its files still in IN go to ERROR, so files not started yet are never imported;
2. every file waiting at 90%: confidence row deleted, version and records deleted, job closed, list unlocked, mail;
3. the failed job is closed and its list unlocked (before, a failed SIX list stayed locked forever).

A file still importing on the other server is not touched. When its import ends, it sees that its file left IN
and rolls itself back.

**Confidence step** (`SixBatchConfidencePoller`, every tick, every server):
1. It waits until no file of the delivery is pending or running.
2. It runs one MERGE per dependent file. Each one is taken with `SELECT … FOR UPDATE SKIP LOCKED` on its
   `CTR_BATCH_EXPORT` row; the MERGE and `BATCH_NODE_ID = node` are committed in one transaction. With 2
   servers, STRUCT and OPT are merged in parallel, each exactly once. If a server dies, Oracle rolls back and
   the other server does the merge.
3. When all merges are done, one server takes the instrument row and finishes:
   - files are deleted from IN;
   - the filtered export is created and the confidence row deleted, in one transaction, for each file;
   - each job goes to 100% and is closed, and each list is unlocked.

   If that server dies, the other one takes over (via heartbeat), and no export is created twice.

**Keep-alive**: files waiting at 90% get `incrementExportBatchForSix(job, 0)` (only `lastUpdateDate` moves), and
only from a file that is really progressing (import batches, MERGE). If the running file is stuck or its
server is dead, nothing refreshes and the existing 30-minute check still alerts.

**Rule everywhere:** files first (deleted or moved to ERROR), unlock last.

## Tested (harness with an in-memory DB and real IN/ERROR folders, 2 simulated servers)

| # | Scenario | Result |
|---|---|---|
| 1 | 1 server, 3 files OK, INSTR imported last | 2 merges, 3 exports, 100%, IN empty, unlocked |
| 2 | INSTR + STRUCT at 90%, OPT fails | 3 versions deleted, 3 files in ERROR, all unlocked, nothing exported |
| 3 | INSTR fails first | STRUCT/OPT moved to ERROR, never imported |
| 4 | 2 servers: B's STRUCT fails while A imports INSTR | A rolls itself back at the end; 3 files in ERROR |
| 5 | 2 servers tick together | STRUCT merged on A, OPT on B; 3 exports (not 6) |
| 6 | only INSTR + STRUCT | 1 merge, 2 exports |
| 7 | A dies while finishing | B takes over, 3 exports in total, none twice |
| 8 | MERGE fails | whole delivery rolled back |
| 9 | keep-alive | waiting file touched once a minute at most, bar stays at 90%; idle tick does not touch |
| 10 | same file names sent again after an error | imported normally |

## Before deploying

- Check there is no SIX delivery half done from the old code. `SELECT * FROM CTR_BATCH_EXPORT WHERE BATCH_TYPE = 'SIX_CONFIDENCE_VALUES'`
  should return 0 rows, and the SIX lists should have no lock (`CTR_LIST_IMPORT_NODE_LOCK`). A SIX list left
  locked by an earlier failed import must be unlocked once by hand.
- Check in a test environment that `JOB_PARAMS` of a `BATCH_IMPORT_SIXRAW` job contains the file name. It is read
  as JSON (`filenames`), with a text search as fallback. The log line `SIX delivery <key> ...` shows the key found.

## Notes

- A failed SIX job now gets an end date, like failed DJ imports today (`finishImportExecutionByRequesterUser`).
  The error is reported by mail and in the list errors, as before.
- `StructureFileRepository` / `OptionsFileRepository.bulkUpdateConfidenceFromInstrument` are no longer called
  (the MERGE is unchanged, now built in `SixConfidenceStore`). They can be removed later.
- The MERGE logs `confidence of X updated ...: N rows in Ns`. If it is slow, the next step is a covering index
  `SIX_INSTRUMENTS (VERSION_ID, CH_VALOR, CONFIDENCE_LEVEL)`. Not added until measured.
- Part 4 reminder: the cleanup of non-latest SIX versions (`deleteNonLatestSixRecordsFromDB`, now commented out
  in `ImportService`) must run only after a delivery has finished. That belongs to Part 4.
