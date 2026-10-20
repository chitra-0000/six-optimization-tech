package com.bnpp.regliss.service.six;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// TODO: re-add project imports for: ListType, ImportedFile, ImportedFileRepository, BatchJobExecution,
// BatchJobExecutionRepository, Version, VersionRepository, SixFilters, SixSubFilters, SixFiltersRepository

public class SixXmlGenerationServiceTest {

    private static final String XSD = "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">"
            + "<xs:element name=\"list\"><xs:complexType><xs:sequence>"
            + "<xs:any processContents=\"skip\" minOccurs=\"0\" maxOccurs=\"unbounded\"/>"
            + "</xs:sequence></xs:complexType></xs:element></xs:schema>";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private SixXmlGenerationService service;
    private ImportedFileRepository importedFileRepository;
    private BatchJobExecutionRepository batchJobExecutionRepository;
    private VersionRepository versionRepository;
    private SixFiltersRepository sixFiltersRepository;
    private Path in;
    private Path holding;

    @Before
    public void setUp() throws IOException {
        service = new SixXmlGenerationService();
        importedFileRepository = mock(ImportedFileRepository.class);
        batchJobExecutionRepository = mock(BatchJobExecutionRepository.class);
        versionRepository = mock(VersionRepository.class);
        sixFiltersRepository = mock(SixFiltersRepository.class);
        ReflectionTestUtils.setField(service, "importedFileRepository", importedFileRepository);
        ReflectionTestUtils.setField(service, "batchJobExecutionRepository", batchJobExecutionRepository);
        ReflectionTestUtils.setField(service, "versionRepository", versionRepository);
        ReflectionTestUtils.setField(service, "sixFiltersRepository", sixFiltersRepository);
        in = tmp.newFolder("dj", "IN").toPath().toAbsolutePath().normalize();
        holding = in.getParent().resolve("six-export-holding");
        ReflectionTestUtils.setField(service, "baseTempFolder", in.toFile());
        File xsd = tmp.newFile("custom.xsd");
        Files.write(xsd.toPath(), XSD.getBytes(StandardCharsets.UTF_8));
        service.customAutomaticXsd = xsd;
        ReflectionTestUtils.setField(service, "holdingDirectory", "");

        ImportedFile file = mock(ImportedFile.class);
        when(file.getOriginalFileName()).thenReturn("GSM_1_202_EU_INSTR_FULL_20261004_101500.xml");
        when(importedFileRepository.findByVersionId(100L)).thenReturn(file);
        Version latest = mock(Version.class);
        when(latest.getId()).thenReturn(100L);
        when(versionRepository.getLatestVersionOfInstruments()).thenReturn(latest);
        jobs("{\"reason\":\"GENERATION\"}");
    }

    private void jobs(String... params) {
        List<BatchJobExecution> jobs = Arrays.stream(params).map(p -> {
            BatchJobExecution job = mock(BatchJobExecution.class);
            when(job.getJobParams()).thenReturn(p);
            return job;
        }).collect(Collectors.toList());
        when(batchJobExecutionRepository.findAllById(any())).thenReturn(jobs);
    }

    private static ListType listType() {
        return ListType.builder().applicationDate("2026-10-04").juridictionBase("A").label("UK").sourceVersion("3").type("FULL").build();
    }

    private static Set<Long> jobIds() {
        return new HashSet<>(Collections.singletonList(7L));
    }

    private static List<String> names(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
        }
    }

    // ------------------------------------------------------------------ files straight into DJ IN

    @Test
    public void createAndUploadFile_ok_nameFromTheInstrumentFile() throws IOException {
        assertEquals("File generation OK", service.createAndUploadFile(listType(), jobIds()));
        assertEquals(Collections.singletonList("CONVERTER-UK_3_20261004_101500.xml"), names(in));
        String xml = new String(Files.readAllBytes(in.resolve("CONVERTER-UK_3_20261004_101500.xml")), StandardCharsets.UTF_8);
        assertTrue(xml, xml.contains("<!-- filters not available -->"));            // list unknown here
    }

    @Test
    public void createAndUploadFile_errorOnlyLogged() throws IOException {
        Files.write(service.customAutomaticXsd.toPath(), "<not a schema".getBytes(StandardCharsets.UTF_8));
        assertEquals("File generation KO", service.createAndUploadFile(listType(), jobIds()));
        assertEquals(Collections.emptyList(), names(in));                                 // the .tmp file is removed
    }

    @Test
    public void createAndUploadFileOrThrow_errorsThrown() throws IOException {
        Files.write(service.customAutomaticXsd.toPath(), XSD.replace("\"list\"", "\"other\"").getBytes(StandardCharsets.UTF_8));
        ListType list = listType();
        Set<Long> ids = jobIds();
        try {
            service.createAndUploadFileOrThrow(list, ids);
            fail();
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("CONVERTER file CONVERTER-UK_3_20261004_101500.xml not created"));
        }
        try {
            service.createAndUploadFileOrThrow(null, ids);
            fail();
        } catch (NullPointerException | IllegalArgumentException e) {
            assertEquals(Collections.emptyList(), names(in));                             // runtime errors kept as they are
        }
    }

    @Test
    public void fileName_regeneration_timestamp_orLatestInstrumentVersion() throws IOException {
        jobs("x REGENERATION");
        String name = service.createAndUploadFileOrThrow(listType(), jobIds());
        assertTrue(name, name.matches("CONVERTER-UK_3_\\d{8}_\\d{6}\\.xml"));

        jobs(null, "GENERATION");
        Version latest = mock(Version.class);
        when(latest.getId()).thenReturn(100L);
        when(versionRepository.getLatestVersionOfInstruments()).thenReturn(latest);
        assertEquals("CONVERTER-UK_3_20261004_101500.xml", service.createAndUploadFileOrThrow(listType(), jobIds()));

        ImportedFile noDate = mock(ImportedFile.class);
        when(noDate.getOriginalFileName()).thenReturn("README.xml");
        when(importedFileRepository.findByVersionId(101L)).thenReturn(noDate);
        when(latest.getId()).thenReturn(101L);
        assertEquals("CONVERTER-UK_3_.xml", service.createAndUploadFileOrThrow(listType(), jobIds()));
    }

    // ------------------------------------------------------------------ holding folder

    @Test
    public void heldFile_footerWithTheListFilters_thenReleased() throws IOException {
        SixFilters filter = mock(SixFilters.class);
        SixSubFilters sub = mock(SixSubFilters.class);
        when(filter.getFileType()).thenReturn("Instrument File");
        when(filter.getFilterName()).thenReturn("UK");
        when(filter.getActiveSixSubfilters()).thenReturn(new LinkedHashSet<>(Collections.singletonList(sub)));
        when(sixFiltersRepository.getFiltersByListRefAndActiveTrueAndDeletedFalse("951")).thenReturn(Collections.singletonList(filter));

        Path file = service.createHeldFileOrThrow(listType(), jobIds(), "20261004101500_GENERATION", "951", 100L);

        assertEquals(holding.resolve("20261004101500_GENERATION").resolve("951").resolve("CONVERTER-UK_3_20261004_101500.xml"), file);
        String xml = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(xml, xml.contains("<!-- [Instrument File] UK : no condition -->"));
        verify(sub, atLeastOnce()).getActiveSixSubORfilters();
        Files.write(file.getParent().resolve("note.txt"), "x".getBytes(StandardCharsets.UTF_8));       // not an xml: stays

        assertEquals(Collections.singletonList("CONVERTER-UK_3_20261004_101500.xml"),
                service.releaseHeldFiles("20261004101500_GENERATION", "951"));
        assertTrue(Files.exists(in.resolve("CONVERTER-UK_3_20261004_101500.xml")));
        assertTrue(Files.exists(file.getParent().resolve("note.txt")));                          // folder not empty: kept
        assertTrue(service.releaseHeldFiles("other", "951").isEmpty());                         // nothing held
    }

    @Test
    public void heldFile_filtersNotReadable_orNone() throws IOException {
        when(sixFiltersRepository.getFiltersByListRefAndActiveTrueAndDeletedFalse("951")).thenThrow(new IllegalStateException("db"));
        Path file = service.createHeldFileOrThrow(listType(), jobIds(), "D", "951");
        assertTrue(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).contains("<!-- filters not available -->"));

        when(sixFiltersRepository.getFiltersByListRefAndActiveTrueAndDeletedFalse("235")).thenReturn(null);
        Path other = service.createHeldFileOrThrow(listType(), jobIds(), "D", "235", 100L);
        assertTrue(new String(Files.readAllBytes(other), StandardCharsets.UTF_8).contains("<!-- no active filter -->"));

        assertEquals(Arrays.asList("CONVERTER-UK_3_20261004_101500.xml"), service.releaseHeldFiles("D", "951"));
        assertFalse(Files.exists(holding.resolve("D").resolve("951")));                         // empty: removed
        assertTrue(Files.exists(holding.resolve("D")));                                           // list 235 still there
    }

    @Test
    public void heldFile_runtimeErrorOfTheWriter_keptAsItIs() throws IOException {
        SixFilters broken = mock(SixFilters.class);
        when(broken.getFileType()).thenThrow(new IllegalStateException("lazy"));
        when(sixFiltersRepository.getFiltersByListRefAndActiveTrueAndDeletedFalse("951")).thenReturn(Collections.singletonList(broken));
        ListType list = listType();
        Set<Long> ids = jobIds();
        try {
            service.createHeldFileOrThrow(list, ids, "D", "951", 100L);
            fail();
        } catch (IllegalStateException e) {
            assertEquals("lazy", e.getMessage());
        }
        assertEquals(Collections.emptyList(), names(holding.resolve("D").resolve("951")));        // no .tmp left
    }

    @Test
    public void heldFolder_cannotBeCreated() throws IOException {
        Files.createDirectories(holding);
        Files.write(holding.resolve("D"), "a file".getBytes(StandardCharsets.UTF_8));            // a file where the folder goes
        ListType list = listType();
        Set<Long> ids = jobIds();
        try {
            service.createHeldFileOrThrow(list, ids, "D", "951");
            fail();
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("Holding folder "));
        }
    }

    @Test
    public void holdingRoot_configuredOrNextToDjIn_neverInside() {
        assertEquals(holding, service.holdingRoot());
        Path configured = tmp.getRoot().toPath().resolve("held");
        ReflectionTestUtils.setField(service, "holdingDirectory", " " + configured + " ");
        assertEquals(configured.toAbsolutePath().normalize(), service.holdingRoot());
        ReflectionTestUtils.setField(service, "holdingDirectory", null);
        assertEquals(holding, service.holdingRoot());

        ReflectionTestUtils.setField(service, "holdingDirectory", in.resolve("sub").toString());
        try {
            service.holdingRoot();
            fail();
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("must not be inside the DJ IN folder"));
        }
        ReflectionTestUtils.setField(service, "holdingDirectory", "");
        ReflectionTestUtils.setField(service, "baseTempFolder", Paths.get("/").toFile());
        try {
            service.holdingRoot();
            fail();
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("No folder above the DJ IN folder"));
        }
    }

    @Test
    public void heldFolder_namesMadeSafe() {
        assertEquals(holding.resolve("_").resolve("a_b_c"), service.heldFolder(null, " a/b..c ".replace("..", "_")));
        assertEquals(holding.resolve("_").resolve("__"), service.heldFolder("", ".."));
        assertEquals("1_2", SixXmlGenerationService.holdingFolderName("1 2"));
    }

    // ------------------------------------------------------------------ nightly cleanup

    private Path heldXml(String group, String list, String name) throws IOException {
        Path folder = Files.createDirectories(holding.resolve(group).resolve(list));
        return Files.write(folder.resolve(name), "<list/>".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void heldFiles_deliver_discard() throws IOException {
        assertTrue(service.heldFiles().isEmpty());                                                 // no holding folder yet
        Path a = heldXml("D1_GENERATION", "951", "CONVERTER-A.xml");
        Path b = heldXml("D1_GENERATION", "235", "CONVERTER-B.xml");
        heldXml("D1_GENERATION", "235", "build.tmp");
        Files.createDirectories(holding.resolve("D1_GENERATION").resolve("235").resolve("folder.xml"));
        Files.write(holding.resolve("stray.txt"), "x".getBytes(StandardCharsets.UTF_8));

        List<SixXmlGenerationService.HeldFile> held = service.heldFiles();

        assertEquals(Arrays.asList("D1_GENERATION/235/CONVERTER-B.xml", "D1_GENERATION/951/CONVERTER-A.xml"),
                held.stream().map(Object::toString).collect(Collectors.toList()));
        SixXmlGenerationService.HeldFile first = held.get(0);
        assertEquals("D1_GENERATION", first.getGroup());
        assertEquals("235", first.getListFolder());
        assertEquals(b, first.getPath());
        assertTrue(first.getLastModified().isBefore(LocalDateTime.now().plusMinutes(1)));

        assertEquals("CONVERTER-B.xml", service.deliverHeldFile(first));
        assertTrue(Files.exists(in.resolve("CONVERTER-B.xml")));
        service.discardHeldFile(held.get(1));
        assertFalse(Files.exists(a));
    }

    @Test
    public void deliverOrDiscard_onlyHeldConverterFiles() throws IOException {
        Path outside = tmp.newFile("outside.xml").toPath();
        Path notXml = heldXml("D", "951", "x.tmp");
        for (Path path : Arrays.asList(outside, notXml)) {
            SixXmlGenerationService.HeldFile file = new SixXmlGenerationService.HeldFile("D", "951", path, LocalDateTime.now());
            try {
                service.discardHeldFile(file);
                fail();
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage().startsWith("Not a held CONVERTER file"));
            }
        }
    }

    @Test
    public void removeTempFilesAndEmptyFolders() throws IOException {
        assertEquals(0, service.removeTempFilesAndEmptyFolders(60));                               // no holding folder
        Path oldTmp = heldXml("D1", "951", "old.tmp");
        Files.setLastModifiedTime(oldTmp, FileTime.fromMillis(System.currentTimeMillis() - 2 * 3600_000L));
        Path youngTmp = heldXml("D1", "235", "young.tmp");
        Path xml = heldXml("D1", "235", "CONVERTER-X.xml");
        Path oldXml = heldXml("D2", "952", "CONVERTER-Y.xml");
        Files.setLastModifiedTime(oldXml, FileTime.fromMillis(System.currentTimeMillis() - 2 * 3600_000L));
        Files.createDirectories(holding.resolve("D3").resolve("953").resolve("sub.tmp"));

        assertEquals(1, service.removeTempFilesAndEmptyFolders(60));

        assertFalse(Files.exists(oldTmp));
        assertFalse(Files.exists(holding.resolve("D1").resolve("951")));                          // empty: removed
        assertTrue(Files.exists(youngTmp));
        assertTrue(Files.exists(xml));
        assertTrue(Files.exists(oldXml));
        assertTrue(Files.exists(holding));
    }
}
