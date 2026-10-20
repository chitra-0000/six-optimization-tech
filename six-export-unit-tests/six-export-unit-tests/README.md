# SIX export – unit tests (batch 2: export classes)

Stack: Java 8, JUnit 4, Mockito 4.5.1 (no inline mock maker), spring-test. No new dependency, no production change.
Result: 257 tests, 0 failures (run 4 times). No Thread.sleep, one call per try/catch exception test (S2925 / S5778).

## Test classes (src/test/java, same packages as the classes)
BatchExportPollerTest, BatchExportServiceTest, DynamicSqlBuilderTest, ExportFileWriterTest, ExportRepositoryImplTest,
FilteredSixFilesTest (FilteredInstrumentFile + FilteredStructuredFile), InstrumentRecordTypeBuilderTest, ListTypeBuilderTest,
MacroSamplesTest (business samples, list 225), SixCleanupPollerTest, SixCleanupServiceTest, SixCleanupStoreTest,
SixExportExceptionTest, SixExportProgressTest, SixExportRunGuardTest, SixFileFilterServiceTest, SixFilterExtractExportTest,
SixFilteredPollerTest, SixFilteredRowWriterTest, SixFilteredStoreTest, SixIsinMergeContentTest, SixIsinMergeServiceTest,
SixIsinRecordMergerTest, SixKeepAliveTimerTest, SixRecordStartDatesTest, SixXmlGenerationPollerTest,
SixXmlGenerationServiceTest, StructuredRecordTypeBuilderTest
Test helpers: SixPollerRowFixtures, SixHeldFileFixtures, BeanProperties (test folder only).
import-fix/: SixJdbcBulkWriterTest of batch 1 without an unused import (Sonar S1128).

After copying: Alt+Enter / Optimize Imports where a "TODO: re-add project imports" comment is present.

## Coverage (JaCoCo)
100 % lines and branches: 21 classes. Not reachable from a unit test without changing the class (listed per class):
- BatchExportService, SixFilteredRowWriter, SixFileFilterService (x2): "default" of a switch on an enum whose constants are all handled.
- ExportFileWriter, ExportRepositoryImpl: catch IOException around a ByteArrayOutputStream (never thrown).
- SixFilteredStore: table-name guard on SixFileKind constants (Fortify).
- SixXmlGenerationService: path guard (Fortify), copy fallback when the holding and DJ IN folders are on different mounts,
  folder-removal error log.
- SixXmlGenerationPoller: ISIN null filter after the same-ISIN merge (merge already leaves such rows out).
- SixFilterExtractExport: jobId null (BatchExport id is a primitive long), partial-row guard, and lines that call a method
  which always throws (failAll / stoppedByOther): JaCoCo marks such call lines as missed although they are executed.

## Notes
- ExportRepositoryImplTest / SixExportProgressTest read a JDK/static field with ReflectionTestUtils (fine on Java 8;
  on Java 17+ surefire needs --add-opens java.base/java.util.concurrent=ALL-UNNAMED).
- SixKeepAliveTimerTest takes about 10 s (two 5 s waits of close()).
