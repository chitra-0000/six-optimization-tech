package com.bnpp.regliss.importer.six.service;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// TODO: re-add project imports for: Version, ReglissList, InstrumentFile, InstrumentFilesDto, InstrumentFileMapper

@RunWith(MockitoJUnitRunner.Silent.class)
public class InstrumentFileImportServiceTest {

    @InjectMocks
    private InstrumentFileImportService service;

    @Mock
    private InstrumentFileMapper instrumentFileMapper;
    @Mock
    private SixBatchImportRunner runner;
    @Mock
    private SixJdbcBulkWriter writer;
    @Mock
    private Version version;
    @Mock
    private ReglissList list;

    @SuppressWarnings("unchecked")
    private SixBatchImportRunner.RecordType<InstrumentFilesDto, InstrumentFile> captureType(File expectedFile) throws Exception {
        ArgumentCaptor<SixBatchImportRunner.RecordType> type = ArgumentCaptor.forClass(SixBatchImportRunner.RecordType.class);
        verify(runner).run(type.capture(), eq(expectedFile), eq(version), eq(list), eq(42L));
        return type.getValue();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void fileIsStreamedWithTheRecordTypeOfinstrumentFiles() throws Exception {
        File xml = new File("transformed.xml");
        service.streamAndPersist(xml, version, list, 42L);

        SixBatchImportRunner.RecordType<InstrumentFilesDto, InstrumentFile> type = captureType(xml);
        assertEquals("instrument", ReflectionTestUtils.getField(type, "label"));
        assertEquals("INSTRUMENT_FILE", ReflectionTestUtils.getField(type, "recordElement"));
        assertEquals(InstrumentFileImportService.RECORD_ELEMENT, ReflectionTestUtils.getField(type, "recordElement"));
        assertSame(InstrumentFilesDto.class, ReflectionTestUtils.getField(type, "dtoClass"));

        // DTO -> entity: the mapper of the service
        InstrumentFilesDto dto = mock(InstrumentFilesDto.class);
        InstrumentFile entity = mock(InstrumentFile.class);
        when(instrumentFileMapper.mapperStructuredFile(dto)).thenReturn(entity);
        assertSame(entity, ((Function<InstrumentFilesDto, InstrumentFile>) ReflectionTestUtils.getField(type, "mapper")).apply(dto));

        // batch -> JDBC writer with the list id, version id and the SIX_TARGET foreign key column
        when(list.getId()).thenReturn(11L);
        when(version.getId()).thenReturn(22L);
        List<InstrumentFile> batch = Collections.singletonList(entity);
        ((SixBatchImportRunner.BatchWriter<InstrumentFile>) ReflectionTestUtils.getField(type, "writer")).write(batch, version, list);
        verify(writer).insertBatch(eq(batch), eq(11L), eq(22L), any(Function.class), eq("SIX_INSTRU_ID"));
    }

    @Test
    public void stringIsWrittenToATempFileThatIsDeletedAfterwards() throws Exception {
        AtomicReference<File> used = new AtomicReference<>();
        doAnswer(inv -> {
            File f = inv.getArgument(1);
            used.set(f);
            assertTrue(f.exists());
            assertEquals("<ROOT/>", new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            assertTrue(f.getName().startsWith("six-instrument-"));
            return 0;
        }).when(runner).run(any(), any(File.class), eq(version), eq(list), eq(42L));

        service.streamAndPersist("<ROOT/>", version, list, 42L);

        assertFalse("temp file deleted", used.get().exists());
    }

    @Test
    public void tempFileIsDeletedAlsoWhenTheImportFails() throws Exception {
        AtomicReference<File> used = new AtomicReference<>();
        doAnswer(inv -> {
            used.set(inv.getArgument(1));
            throw new SixImportException("import failed");
        }).when(runner).run(any(), any(File.class), eq(version), eq(list), anyLong());

        try {
            service.streamAndPersist("<ROOT/>", version, list, 42L);
            fail("expected SixImportException");
        } catch (SixImportException e) {
            assertEquals("import failed", e.getMessage());
        }
        assertFalse("temp file deleted", used.get().exists());
    }
}
