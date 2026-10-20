# SIX import – unit tests (batch 1: import classes)

Stack: Java 8, JUnit 4, Mockito 4.5.1 (no inline mock maker), spring-test (ReflectionTestUtils). No new dependency.
Result: 135 tests, 0 failures. Coverage (JaCoCo): 100 % lines on all 14 classes, 100 % branches except 1 (see below).

## Test classes (src/test/java)
importer/six/service: AutomaticSixImportXmlServiceTest, InstrumentFileImportServiceTest, OptionsFileImportServiceTest,
StructuredFileImportServiceTest, SixBatchImportRunnerTest, SixConfidenceStoreTest, SixDeliveryServiceTest,
SixFileKindTest, SixImportExceptionTest, SixImportExecutorTest, SixJdbcBulkWriterTest, SixXmlRecordStreamTest,
SixDeliveryFixtures (test helper, builds Delivery objects for the poller test)
scheduler: AutomaticSixImportFileRepositoryTest, SixBatchConfidencePollerTest

After copying: Alt+Enter / Optimize Imports where a "TODO: re-add project imports" comment is present.

## Production delta (src/main/java) – test hooks only, no behaviour change
- AutomaticSixImportFileRepository: Files.move wrapped in package-private moveFile(...) (test simulates another mount / disk error)
- AutomaticSixImportXmlService: Files.deleteIfExists wrapped in package-private deleteIfExists(File); deleteQuietly no longer static
- SixImportExecutor: wait logic in private finish() (returns the worker error), used by blockingSubmit and waitUntilFinished

## Notes
- Not covered (1 branch): SixDeliveryService.keepWaitingFilesAlive "compareAndSet lost" – only happens when two threads race;
  AtomicLong methods are final, so Mockito 4.5.1 cannot simulate it.
- SixDeliveryService is tested in its latest version (Sonar fix of 15-Oct: finishingDeliveryKey / addWaitingRows ...).
- AutomaticFeedImporter: pending – needs the FeedImporter (superclass) and StaticContextAccessor sources.
