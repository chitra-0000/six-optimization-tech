package com.bnpp.regliss.six.it;

import com.bnpp.regliss.InMemoryDb;
import com.bnpp.regliss.repository.AbstractSpringBootTestBase;
import org.junit.After;
import org.junit.Before;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// TODO: re-add project imports (Alt+Enter / Optimize Imports) for: ReglissList, Version, ImportFileType, ImportedFile,
// AutomaticImportFeedBase, UnicodeBOMInputStream, BatchManagementService, BatchExportService, BatchExportType,
// GenerationReason, AutomaticSixImportXmlService, SixDeliveryService

/**
 * Base of the SIX flow integration tests: real Spring context of the batch, real SIX beans, H2 test database.
 *
 * <ul>
 *   <li>SIX SQL that H2 does not understand is adapted by {@link SixH2Compatibility} (test only, no production change)</li>
 *   <li>the tables are created by Hibernate from the entities ({@code hbm2ddl.auto=create} for this test context)</li>
 *   <li>the SIX schedulers are switched off: every test calls the pollers itself, in a fixed order, without waiting</li>
 *   <li>the SIX IN / IGNORE / ERROR, DJ IN and holding folders are in a temporary folder of this test class</li>
 *   <li>the data each test creates is deleted before and after it; as this deletes every SIX row, the tests refuse to
 *       run on anything else than the H2 test database ({@link InMemoryDb})</li>
 * </ul>
 * A SIX file is imported the way ImportService.doAutomaticSixImportFeed does it (job, version, XSLT + bulk write,
 * imported file, confidence request, delivery check), without the non-SIX steps (VersionService integrate/certify).
 */
@InMemoryDb
@Import(SixH2Compatibility.class)
@TestPropertySource(properties = {
        "spring.jpa.properties.hibernate.hbm2ddl.auto=create",
        "task.batch.export.generation=-",
        "task.dj_import.cron=-",
        "task.six.cleanup.cron=-",
        "allow.six.file.integration=INSTR,STRUCT",
        "six.file.prefix=GSM",
        "six.cmic.list=901",
        "six.e014071.list=902",
        "six.exclusion.list=903"
})
public abstract class AbstractSixFlowIT extends AbstractSpringBootTestBase {

    protected static final String DELIVERY = "20261020_143113";
    protected static final String NEXT_DELIVERY = "20261021_143113";
    protected static final String OUTPUT_LIST = "077";   // leading zero: kept as it is in GENERATED_FILE_IDS

    private static final Path ROOT = createRoot();

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    protected AutomaticSixImportXmlService automaticSixImportXmlService;
    @Autowired
    protected BatchManagementService batchManagementService;
    @Autowired
    protected BatchExportService batchExportService;
    @Autowired
    protected SixDeliveryService sixDeliveryService;

    private final List<Long> createdLists = new ArrayList<>();

    @DynamicPropertySource
    static void sixFolders(DynamicPropertyRegistry registry) {
        registry.add("automatic.import.six.IN.directory", () -> ROOT.resolve("six/in").toString());
        registry.add("automatic.import.six.IGNORE.directory", () -> ROOT.resolve("six/ignore").toString());
        registry.add("automatic.import.six.ERROR.directory", () -> ROOT.resolve("six/error").toString());
        registry.add("automatic.import.IN.directory", () -> ROOT.resolve("dj/in").toString());
        registry.add("six.export.holding.directory", () -> ROOT.resolve("holding").toString());
        registry.add("temp.file.folder", () -> ROOT.resolve("tmp").toString());
    }

    @Before
    public void cleanBefore() {
        String url = jdbc.execute((ConnectionCallback<String>) connection -> connection.getMetaData().getURL());
        assertTrue("The SIX integration tests delete SIX data: they only run on the H2 test database, not on " + url,
                url != null && url.startsWith("jdbc:h2:"));
        cleanSixData();
        cleanFolders();
    }

    @After
    public void cleanAfter() {
        cleanSixData();
        cleanFolders();
    }

    // ------------------------------------------------------------------------------------------ data

    /** A raw SIX list (SIX_INSTRUMENTS_FILE, SIX_STRUCTURED_FILE ...). */
    protected ReglissList rawList(ImportFileType type, String reference) {
        return newList(type, reference);
    }

    /** A SIX output list (SIX_MAIN_FILE): the filtered export writes one CONVERTER file for it. */
    protected ReglissList outputList(String reference) {
        return newList(ImportFileType.SIX_MAIN_FILE, reference);
    }

    private ReglissList newList(ImportFileType type, String reference) {
        ReglissList list = inTransaction(() -> {
            ReglissList l = new ReglissList();
            l.setName("SIX IT " + reference);
            l.setShortName("SIXIT" + reference);
            l.setReference(reference);
            l.setActive(true);
            l.setDeleted(false);
            l.getImportConfiguration().setImportFileType(type);
            l.getImportConfiguration().setAllowAutoUpload(true);
            em.persist(l);
            return l;
        });
        createdLists.add(list.getId());
        return list;
    }

    /** The ids of one imported SIX file. */
    protected static final class ImportedSix {
        final String fileName;
        final long jobId;
        final long versionId;

        ImportedSix(String fileName, long jobId, long versionId) {
            this.fileName = fileName;
            this.jobId = jobId;
            this.versionId = versionId;
        }
    }

    /** Puts the file in the SIX IN folder (as CFT does); it is imported later with {@link #importFile}. */
    protected void deliverToSixIn(String fileName, String xml) {
        write(sixIn().resolve(fileName), xml);
    }

    /**
     * Imports a file already in the SIX IN folder: job, version, XSLT + bulk write, imported-file record, confidence
     * request (SIX_CONFIDENCE_VALUES), then the delivery check of SixDeliveryService.
     */
    protected ImportedSix importFile(ReglissList list, String fileName) {
        AutomaticImportFeedBase feed = feed(fileName);
        long jobId = batchManagementService.createNewSixRawAutoBatchExecutionInTx(feed, list);
        Version version = newVersion(list.getId(), fileName);
        byte[] content = read(sixIn().resolve(fileName));
        automaticSixImportXmlService.importAutomaticFileWithoutTx(() -> bomStream(content), list, version.getId(), jobId);
        batchExportService.persistSixImportedFileGenerationStubInTx(BatchExportType.SIX_CONFIDENCE_VALUES,
                GenerationReason.GENERATION, version, jobId);
        sixDeliveryService.afterSuccessfulImport(feed);
        return new ImportedSix(fileName, jobId, version.getId());
    }

    /** {@link #deliverToSixIn} then {@link #importFile}. */
    protected ImportedSix deliverAndImport(ReglissList list, String fileName, String xml) {
        deliverToSixIn(fileName, xml);
        return importFile(list, fileName);
    }

    private Version newVersion(long listId, String fileName) {
        return inTransaction(() -> {
            ReglissList list = em.find(ReglissList.class, listId);
            Version version = new Version();
            version.setVersionNumber((long) list.getVersions().size() + 1);
            version.setImportFileType(list.getImportFileType());
            list.addVersion(version);
            em.persist(version);
            ImportedFile file = new ImportedFile();
            file.setStatus(ImportedFile.UploadStatus.IMPORTED_OK);
            file.setUploadType(ImportedFile.UploadType.AUTO);
            file.setUploadDate(LocalDateTime.now());
            file.setFileName(fileName);
            file.setOriginalFileName(fileName);
            file.setVersion(version);
            file.setList(list);
            em.persist(file);
            return version;
        });
    }

    private static AutomaticImportFeedBase feed(String fileName) {
        AutomaticImportFeedBase feed = mock(AutomaticImportFeedBase.class);
        when(feed.getXmlDataFileName()).thenReturn(fileName);
        when(feed.getAllFileNames()).thenReturn(Collections.singletonList(fileName));
        when(feed.getFileNamesToImport()).thenReturn(Collections.singletonList(fileName));
        when(feed.getOriginalFileName()).thenReturn(fileName);
        when(feed.isSixAutomatic()).thenReturn(true);
        when(feed.isAuto()).thenReturn(true);
        return feed;
    }

    private static UnicodeBOMInputStream bomStream(byte[] content) {
        try {
            return new UnicodeBOMInputStream(new ByteArrayInputStream(content));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    protected <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    // ------------------------------------------------------------------------------------------ checks

    protected long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    protected List<String> exportRequests(String batchType) {
        return jdbc.queryForList("SELECT GENERATED_FILE_IDS FROM CTR_BATCH_EXPORT WHERE BATCH_TYPE = ? ORDER BY ID", String.class, batchType);
    }

    protected boolean jobFinished(long jobId) {
        return count("SELECT COUNT(*) FROM CTR_BATCH_JOB_EXECUTION WHERE ID = ? AND END_DATE IS NOT NULL AND PERCENT = 100", jobId) == 1;
    }

    protected boolean jobOpen(long jobId) {
        return count("SELECT COUNT(*) FROM CTR_BATCH_JOB_EXECUTION WHERE ID = ? AND END_DATE IS NULL", jobId) == 1;
    }

    protected static Path sixIn() {
        return ROOT.resolve("six/in");
    }

    protected static Path sixError() {
        return ROOT.resolve("six/error");
    }

    protected static Path djIn() {
        return ROOT.resolve("dj/in");
    }

    protected static List<String> fileNames(Path folder) {
        if (!Files.isDirectory(folder)) {
            return Collections.emptyList();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    protected static String text(Path file) {
        return new String(read(file), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------------------------------ clean-up

    private void cleanSixData() {
        for (String table : new String[]{"FILTERED_SIX_TARGET", "FILTERED_SIX_INSTRUMENTS", "FILTERED_SIX_STRUCTURED",
                "FILTERED_SIX_OPTION", "SIX_FILTERED_POLLER", "SIX_TARGET", "SIX_INSTRUMENTS", "SIX_STRUCTURED", "SIX_OPTION"}) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("DELETE FROM CTR_BATCH_EXPORT WHERE BATCH_TYPE IN ('SIX_CONFIDENCE_VALUES', 'SIX_FILTERED_FILE_GENERATION',"
                + " 'SIX_FILTERED_FILE_REGENERATION')");
        jdbc.update("DELETE FROM CTR_BATCH_JOB_EXECUTION WHERE JOB_TYPE IN ('BATCH_IMPORT_SIXRAW', 'BATCH_EXPORT_SIXRAW')");
        List<Long> lists = new ArrayList<>(createdLists);
        lists.addAll(jdbc.queryForList("SELECT ID FROM REGLISS_LIST WHERE NAME LIKE 'SIX IT %'", Long.class));
        inTransaction(() -> {
            em.createQuery("SELECT f FROM ImportedFile f WHERE f.version.list.id IN :lists", ImportedFile.class)
                    .setParameter("lists", withDummy(lists)).getResultList().forEach(em::remove);
            for (Long id : lists) {
                ReglissList list = em.find(ReglissList.class, id);
                if (list != null) {
                    em.remove(list);
                }
            }
            return null;
        });
        createdLists.clear();
    }

    private static List<Long> withDummy(List<Long> ids) {
        return ids.isEmpty() ? Collections.singletonList(-1L) : ids;
    }

    private static void cleanFolders() {
        try (Stream<Path> paths = Files.walk(ROOT)) {
            paths.sorted(Comparator.reverseOrder()).filter(p -> !p.equals(ROOT)).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (String folder : new String[]{"six/in", "six/ignore", "six/error", "dj/in", "holding", "tmp"}) {
            ROOT.resolve(folder).toFile().mkdirs();
        }
    }

    private static Path createRoot() {
        try {
            Path root = Files.createTempDirectory("six-it-");
            root.toFile().deleteOnExit();
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path file, String content) {
        try {
            Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
