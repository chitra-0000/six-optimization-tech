package com.bnpp.regliss.importer.six.service;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// TODO: re-add project imports for: ReglissList, ImportConfiguration, ImportFileType, Version, VersionRepository,
// ReglissException, UnicodeBOMInputStream, InstrumentFile, StructuredFile, OptionsFile

public class AutomaticSixImportXmlServiceTest {

    private static final String IDENTITY_XSLT = "<xsl:stylesheet version=\"1.0\" xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\">"
            + "<xsl:template match=\"@*|node()\"><xsl:copy><xsl:apply-templates select=\"@*|node()\"/></xsl:copy></xsl:template>"
            + "</xsl:stylesheet>";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private AutomaticSixImportXmlService service;
    private VersionRepository versionRepository;
    private InstrumentFileImportService instrumentService;
    private StructuredFileImportService structuredService;
    private OptionsFileImportService optionsService;
    private SixJdbcBulkWriter bulkWriter;
    private Version version;
    private File tempFolder;
    private String javaTmp;

    @Before
    public void setUp() throws IOException {
        javaTmp = System.getProperty("java.io.tmpdir");
        service = spy(new AutomaticSixImportXmlService());
        versionRepository = mock(VersionRepository.class);
        instrumentService = mock(InstrumentFileImportService.class);
        structuredService = mock(StructuredFileImportService.class);
        optionsService = mock(OptionsFileImportService.class);
        bulkWriter = mock(SixJdbcBulkWriter.class);
        version = mock(Version.class);
        tempFolder = tmp.newFolder("six-temp");
        ReflectionTestUtils.setField(service, "versionRepository", versionRepository);
        ReflectionTestUtils.setField(service, "instrumentFileImportService", instrumentService);
        ReflectionTestUtils.setField(service, "structuredFileImportService", structuredService);
        ReflectionTestUtils.setField(service, "optionsFileImportService", optionsService);
        ReflectionTestUtils.setField(service, "bulkWriter", bulkWriter);
        ReflectionTestUtils.setField(service, "baseTempFolder", tempFolder.getAbsolutePath());
        ReflectionTestUtils.setField(service, "transformerFileSixInstrumentAutomatic", xslt());
        ReflectionTestUtils.setField(service, "transformerFileSixStructureAutomatic", xslt());
        ReflectionTestUtils.setField(service, "transformerFileSixOptionAutomatic", xslt());
        when(versionRepository.findById(5L)).thenReturn(Optional.of(version));
    }

    @After
    public void restoreTmp() {
        System.setProperty("java.io.tmpdir", javaTmp);
    }

    private static Resource xslt() {
        return new ByteArrayResource(IDENTITY_XSLT.getBytes(StandardCharsets.UTF_8));
    }

    private static ReglissList list(ImportFileType type) {
        ReglissList list = mock(ReglissList.class);
        ImportConfiguration configuration = mock(ImportConfiguration.class);
        when(list.getImportConfiguration()).thenReturn(configuration);
        when(configuration.getImportFileType()).thenReturn(type);
        when(list.getImportFileType()).thenReturn(type);
        return list;
    }

    private static Supplier<UnicodeBOMInputStream> xml(String content) {
        return () -> {
            try {
                return new UnicodeBOMInputStream(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    // ------------------------------------------------------------------------------------------ success

    @Test
    public void instrument_imported_tempFileRemoved() throws Exception {
        ReglissList list = list(ImportFileType.SIX_INSTRUMENTS_FILE);
        ArgumentCaptor<File> file = ArgumentCaptor.forClass(File.class);
        service.importAutomaticFileWithoutTx(xml("<root><a>1</a></root>"), list, 5L, 9L);
        verify(instrumentService).streamAndPersist(file.capture(), eq(version), eq(list), eq(9L));
        assertFalse(file.getValue().exists());
        assertTrue(file.getValue().getName().startsWith("six-import-six_instruments_file-"));
        assertEquals(tempFolder, file.getValue().getParentFile());
    }

    @Test
    public void structured_and_options_imported_templatesCompiledOnce() throws Exception {
        Resource structuredXslt = spy(xslt());
        ReflectionTestUtils.setField(service, "transformerFileSixStructureAutomatic", structuredXslt);
        ReglissList structured = list(ImportFileType.SIX_STRUCTURED_FILE);
        service.importAutomaticFileWithoutTx(xml("<r/>"), structured, 5L, 1L);
        service.importAutomaticFileWithoutTx(xml("<r/>"), structured, 5L, 2L);
        verify(structuredService, times(2)).streamAndPersist(any(File.class), eq(version), eq(structured), anyLong());
        verify(structuredXslt, times(1)).getInputStream();

        ReglissList options = list(ImportFileType.SIX_OPTIONS_FILE);
        service.importAutomaticFileWithoutTx(xml("<r/>"), options, 5L, 3L);
        verify(optionsService).streamAndPersist(any(File.class), eq(version), eq(options), eq(3L));
        verify(instrumentService, never()).streamAndPersist(any(File.class), any(), any(), anyLong());
    }

    @Test
    public void otherListType_nothingWritten() throws Exception {
        ReglissList list = list(ImportFileType.SIX_INSTRUMENTS_FILE);
        when(list.getImportFileType()).thenReturn(ImportFileType.CUSTOM);
        service.importAutomaticFileWithoutTx(xml("<r/>"), list, 5L, 3L);
        verify(instrumentService, never()).streamAndPersist(any(File.class), any(), any(), anyLong());
        verify(structuredService, never()).streamAndPersist(any(File.class), any(), any(), anyLong());
        verify(optionsService, never()).streamAndPersist(any(File.class), any(), any(), anyLong());
    }

    // ------------------------------------------------------------------------------------------ failures

    @Test
    public void versionNotFound_reglissException_nothingRemoved() throws Exception {
        Supplier<UnicodeBOMInputStream> input = xml("<r/>");
        ReglissList instruments = list(ImportFileType.SIX_INSTRUMENTS_FILE);
        try {
            service.importAutomaticFileWithoutTx(input, instruments, 6L, 3L);
            fail();
        } catch (ReglissException e) {
            verify(bulkWriter, never()).deleteVersion(any(), anyLong());
        }
    }

    @Test
    public void writeFails_partialVersionRemoved_perType() throws Exception {
        RuntimeException boom = new IllegalStateException("db");
        ReglissList instrument = list(ImportFileType.SIX_INSTRUMENTS_FILE);
        doThrow(boom).when(instrumentService).streamAndPersist(any(File.class), any(), any(), anyLong());
        when(bulkWriter.deleteVersion(InstrumentFile.class, 5L)).thenReturn(10);
        expectWrapped(instrument, boom);
        verify(bulkWriter).deleteVersion(InstrumentFile.class, 5L);

        ReglissList structured = list(ImportFileType.SIX_STRUCTURED_FILE);
        doThrow(boom).when(structuredService).streamAndPersist(any(File.class), any(), any(), anyLong());
        when(bulkWriter.deleteVersion(StructuredFile.class, 5L)).thenThrow(new IllegalStateException("cleanup"));
        expectWrapped(structured, boom);

        ReglissList options = list(ImportFileType.SIX_OPTIONS_FILE);
        doThrow(boom).when(optionsService).streamAndPersist(any(File.class), any(), any(), anyLong());
        expectWrapped(options, boom);
        verify(bulkWriter).deleteVersion(OptionsFile.class, 5L);
    }

    private void expectWrapped(ReglissList list, RuntimeException cause) throws Exception {
        Supplier<UnicodeBOMInputStream> input = xml("<r/>");
        try {
            service.importAutomaticFileWithoutTx(input, list, 5L, 3L);
            fail();
        } catch (ReglissException e) {
            assertSame(cause, e.getCause());
        }
    }

    @Test
    public void writeFails_withReglissException_rethrownAsIs() throws Exception {
        ReglissException own = new ReglissException("own");
        doThrow(own).when(instrumentService).streamAndPersist(any(File.class), any(), any(), anyLong());
        Supplier<UnicodeBOMInputStream> input = xml("<r/>");
        ReglissList instruments = list(ImportFileType.SIX_INSTRUMENTS_FILE);
        try {
            service.importAutomaticFileWithoutTx(input, instruments, 5L, 3L);
            fail();
        } catch (ReglissException e) {
            assertSame(own, e);
        }
    }

    @Test
    public void badInputXml_transformError_tempFileRemoved() throws Exception {
        expectFailure(list(ImportFileType.SIX_INSTRUMENTS_FILE), xml("<not-closed>"));
        assertEquals(0, tempFolder.list().length);
    }

    @Test
    public void xsltCannotBeRead() throws Exception {
        Resource broken = mock(Resource.class);
        when(broken.getInputStream()).thenThrow(new IOException("missing"));
        ReflectionTestUtils.setField(service, "transformerFileSixOptionAutomatic", broken);
        expectFailure(list(ImportFileType.SIX_OPTIONS_FILE), xml("<r/>"));
    }

    @Test
    public void xsltInvalid() throws Exception {
        ReflectionTestUtils.setField(service, "transformerFileSixStructureAutomatic",
                new ByteArrayResource("<not-xslt/>".getBytes(StandardCharsets.UTF_8)));
        expectFailure(list(ImportFileType.SIX_STRUCTURED_FILE), xml("<r/>"));
    }

    @Test
    public void notASixFileType() throws Exception {
        expectFailure(list(ImportFileType.SIX_MAIN_FILE), xml("<r/>"));
    }

    private void expectFailure(ReglissList list, Supplier<UnicodeBOMInputStream> input) throws Exception {
        try {
            service.importAutomaticFileWithoutTx(input, list, 5L, 3L);
            fail();
        } catch (ReglissException e) {
            verify(bulkWriter, never()).deleteVersion(any(), anyLong());
        }
    }

    // ------------------------------------------------------------------------------------------ temp folder

    @Test
    public void tempFolder_default_javaTmpDir() throws Exception {
        File javaTmpDir = tmp.newFolder("java-tmp");
        System.setProperty("java.io.tmpdir", javaTmpDir.getAbsolutePath());
        ReflectionTestUtils.setField(service, "baseTempFolder", "  ");
        service.importAutomaticFileWithoutTx(xml("<r/>"), list(ImportFileType.SIX_INSTRUMENTS_FILE), 5L, 3L);
        ReflectionTestUtils.setField(service, "baseTempFolder", null);
        service.importAutomaticFileWithoutTx(xml("<r/>"), list(ImportFileType.SIX_INSTRUMENTS_FILE), 5L, 3L);
        verify(instrumentService, times(2)).streamAndPersist(any(File.class), any(), any(), anyLong());
    }

    @Test
    public void tempFolder_createdWhenMissing() throws Exception {
        File missing = new File(tempFolder, "a/b");
        ReflectionTestUtils.setField(service, "baseTempFolder", missing.getAbsolutePath());
        service.importAutomaticFileWithoutTx(xml("<r/>"), list(ImportFileType.SIX_INSTRUMENTS_FILE), 5L, 3L);
        assertTrue(missing.isDirectory());
    }

    @Test
    public void tempFolder_cannotBeCreated() throws Exception {
        File regularFile = tmp.newFile("plain.txt");
        ReflectionTestUtils.setField(service, "baseTempFolder", new File(regularFile, "sub").getAbsolutePath());
        expectFailure(list(ImportFileType.SIX_INSTRUMENTS_FILE), xml("<r/>"));
    }

    @Test
    public void tempFolder_notListable() throws Exception {
        System.setProperty("java.io.tmpdir", new File(tempFolder, "does-not-exist").getAbsolutePath());
        ReflectionTestUtils.setField(service, "baseTempFolder", "");
        expectFailure(list(ImportFileType.SIX_INSTRUMENTS_FILE), xml("<r/>"));
    }

    @Test
    public void staleTempFiles_removed() throws Exception {
        File old = staleFile("six-import-old.xml", true);
        File recent = staleFile("six-import-recent.xml", false);
        File other = staleFile("other-old.xml", true);
        File otherExtension = staleFile("six-import-old.txt", true);
        File locked = staleFile("six-import-locked.xml", true);
        doThrow(new IOException("locked")).when(service).deleteIfExists(locked);

        service.importAutomaticFileWithoutTx(xml("<r/>"), list(ImportFileType.SIX_INSTRUMENTS_FILE), 5L, 3L);
        assertFalse(old.exists());
        assertTrue(recent.exists());
        assertTrue(other.exists());
        assertTrue(otherExtension.exists());
        assertTrue(locked.exists());
    }

    private File staleFile(String name, boolean old) throws IOException {
        File f = new File(tempFolder, name);
        Files.write(f.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        if (old) {
            assertTrue(f.setLastModified(System.currentTimeMillis() - 25L * 3600 * 1000));
        }
        return f;
    }

    @Test
    public void tempFileCannotBeDeleted_deletedAtExit() throws Exception {
        doThrow(new IOException("locked")).when(service).deleteIfExists(any(File.class));
        ArgumentCaptor<File> file = ArgumentCaptor.forClass(File.class);
        service.importAutomaticFileWithoutTx(xml("<r/>"), list(ImportFileType.SIX_INSTRUMENTS_FILE), 5L, 3L);
        verify(instrumentService).streamAndPersist(file.capture(), any(), any(), anyLong());
        assertTrue(file.getValue().exists());
        Files.delete(file.getValue().toPath());
    }
}
