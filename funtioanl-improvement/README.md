# SIX performance A, B, E

Built on `updatedpath` (`six-part4-cleanup`, latest) and `originalpath` (latest, with `Record.java`). Java 8, Spring Boot 2.7.
No table, column or Flyway change. The XSD validation and the XML content are unchanged.

## Files

| File | Package | Change |
|---|---|---|
| `SixXmlGenerationPoller` | scheduler | **A.** Keep-alive timer around the build of each CONVERTER file (base: `six-part4-cleanup/six-part3-final`) |
| `SixKeepAliveTimer` | importer.six.service | **A. New.** Small timer: one refresh every N seconds, stopped when the build ends |
| `SixFileFilterService` | importer.six.extractor | **B.** Filter reads (include + CMIC / E014071 queries) use their own template with a fetch size (base: `six-part3-final`) |
| `SixRecordStartDates` | importer.six.extractor | **E. New.** Start dates of all records of a list's last version, one query |
| `InstrumentRecordTypeBuilder` | importer.six.extractor | **E.** Uses `SixRecordStartDates` instead of one `findByExternalReferenceAndVersionId` per record (originalpath class) |
| `StructuredRecordTypeBuilder` | importer.six.extractor | **E.** Same (originalpath class) |

`RecordRepository` (domain jar) is not changed. `findByExternalReferenceAndVersionId` is simply no longer called by the two builders.

## A. Keep-alive during the XML build

- From the start of the build of one list until its file is held (`BUILT`), every 60 s a daemon timer calls the existing `keepAlive(build)`:
  - `batchProgressService.incrementExportBatchForSix(job, 0)` for each job of the list: `percent + 0` and `LAST_UPDATE_DATE = now` (the field read by the UI and by `isJobWorking`);
  - `touchBuilding`: `UPDATED_TIME = now` on the list's `BUILDING` rows (read by the other server's release and by the cleanup).
- The timer is closed by try-with-resources: build OK, build failed, `OutOfMemoryError` -> stopped at once. Server crash -> the daemon thread dies with the JVM.
- A build still running after `six.export.build.keepalive.max.minutes` (default 120) is no longer refreshed: a hanging build turns KO like a dead one.
- An error in one refresh is logged (WARN) and the next tick tries again.

## B. Fetch size of the filter reads

- Before: the shared `NamedParameterJdbcTemplate` has no fetch size -> Oracle driver default 10 rows per round trip (~110 000 round trips for 92 581 instruments with 1.1 million targets).
- Now: the two filter reads use a template on the **same DataSource** (same transactions, same exception translation, same timeout) with fetch size `six.export.filter.fetch.size` (default 500). Every other query and all writes (JDBC batches) are unchanged.
- 500, not 2000: the driver reserves buffer for every fetched row and every column, and these rows have wide text columns (`SANCTIONS_RATIONALE`). Raise it in the lower environment if memory allows; the log line `SIX filter reads: fetch size N` shows the value in use.

## E. Start dates: one query per list

- Before: for each of the ~49 000 records of a list, `findByExternalReferenceAndVersionId(chValor / hostCh, lastVersionId)` loaded a full `Record` -> 15 to 25 min per list (same in the original code: 03-10 log, list 235: 16:48:07 -> 17:07:47).
- Now: one JPQL query per builder call (2 per list), only two columns:
  ```
  SELECT r.externalReference, r.startDateStr FROM Record r JOIN Version v
    ON r.versionLinks.listId = v.list.id AND v.id = :versionId
   AND r.versionLinks.fromVersionId <= :versionId AND r.versionLinks.toVersionId >= :versionId
  ```
  (same join and conditions as `findByExternalReferenceAndVersionId`, without the external reference), then in-memory lookups.
- Same result for every record:
  - record found with a start date -> that date;
  - not found, or found without start date -> application date of the file name (unchanged fallback);
  - list without version -> fallback, no query;
  - NULL external reference -> fallback (SQL `=` never matches NULL);
  - the same external reference twice in the version -> `IncorrectResultSizeDataAccessException`, as the single-result query threw before;
  - exact comparison, like the SQL `=`.
- Log line: `SIX XML step: start dates of N records of version V read in X ms`.

## Properties (all optional)

```properties
six.export.build.keepalive.seconds=60
six.export.build.keepalive.max.minutes=120
six.export.filter.fetch.size=500
```

## Tests (harness: real classes, SQLite, stub Spring classes)

| Test | Result |
|---|---|
| A: 3.5 s build, 1 s period, against the same build with the timer disabled | +3 `BUILDING` refreshes, +6 job refreshes (2 jobs); job `LAST_UPDATE_DATE` recent although it was 40 min old; list `BUILT` |
| A: after the build / after an `OutOfMemoryError` | No refresh any more, no timer thread left |
| A: error in a refresh / maximum reached / period 0 | Timer continues / stops / uses 1 s |
| B | Filter reads: own template, fetch size 500, same timeout; shared template unchanged; property 0 -> 1 |
| E | One query with the last version id and the same conditions; list without version: no query; **60 000 random lookups identical** to the per-record query (found, no date, not found, NULL, case, duplicates) |
| Regression | 65 export-release checks and 73 cleanup checks still OK |

The two record builders use Lombok builders and could not be compiled here: the change is limited to `getRecordApplicationDate`, its callers and the injected field (see the diff). Please compile in the IDE.

## Check in the lower environment

- XML step: the 15 to 25 min per list should drop to about 1 to 2 min; look for the `start dates of N records` line (N about the record count of the list).
- Filter step: compare `filter: ... rows read ... in N ms` with the 09-10 log (42 s STRUCT / 115 s INSTR per list).
- Compare the 4 CONVERTER files with the previous run (only the generation date should differ). The DJ import delta should stay small.
