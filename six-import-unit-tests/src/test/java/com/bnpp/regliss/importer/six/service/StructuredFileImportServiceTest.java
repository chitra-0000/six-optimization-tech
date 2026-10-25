package com.bnpp.regliss.importer.six.service;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// TODO: re-add project imports for: Version, ReglissList, StructuredFile, StructuredFileDto, StructuredFileMapper

@RunWith(MockitoJUnitRunner.Silent.class)
public class StructuredFileImportServiceTest {

    @InjectMocks
    private StructuredFileImportService service;

    @Mock
    private StructuredFileMapper structuredFileMapper;
    @Mock
    private SixBatchImportRunner runner;
    @Mock
    private SixJdbcBulkWriter writer;
    @Mock
    private Version version;
    @Mock
    private ReglissList list;

    @SuppressWarnings("unchecked")
    private SixBatchImportRunner.RecordType<StructuredFileDto, StructuredFile> captureType(File expectedFile) throws Exception {
        ArgumentCaptor<SixBatchImportRunner.RecordType> type = ArgumentCaptor.forClass(SixBatchImportRunner.RecordType.class);
        verify(runner).run(type.capture(), eq(expectedFile), eq(version), eq(list), eq(42L));
        return type.getValue();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void fileIsStreamedWithTheRecordTypeOfstructureFiles() throws Exception {
        File xml = new File("transformed.xml");
        service.streamAndPersist(xml, version, list, 42L);

        SixBatchImportRunner.RecordType<StructuredFileDto, StructuredFile> type = captureType(xml);
        assertEquals("structure", ReflectionTestUtils.getField(type, "label"));
        assertEquals("STRUCTURED_FILE", ReflectionTestUtils.getField(type, "recordElement"));
        assertEquals(StructuredFileImportService.RECORD_ELEMENT, ReflectionTestUtils.getField(type, "recordElement"));
        assertSame(StructuredFileDto.class, ReflectionTestUtils.getField(type, "dtoClass"));

        // DTO -> entity: the mapper of the service
        StructuredFileDto dto = mock(StructuredFileDto.class);
        StructuredFile entity = mock(StructuredFile.class);
        when(structuredFileMapper.mapperStructuredFile(dto)).thenReturn(entity);
        assertSame(entity, ((Function<StructuredFileDto, StructuredFile>) ReflectionTestUtils.getField(type, "mapper")).apply(dto));

        // batch -> JDBC writer with the list id, version id and the SIX_TARGET foreign key column
        when(list.getId()).thenReturn(11L);
        when(version.getId()).thenReturn(22L);
        List<StructuredFile> batch = Collections.singletonList(entity);
        ((SixBatchImportRunner.BatchWriter<StructuredFile>) ReflectionTestUtils.getField(type, "writer")).write(batch, version, list);
        verify(writer).insertBatch(eq(batch), eq(11L), eq(22L), any(Function.class), eq("SIX_STRUCT_ID"));
    }
}
