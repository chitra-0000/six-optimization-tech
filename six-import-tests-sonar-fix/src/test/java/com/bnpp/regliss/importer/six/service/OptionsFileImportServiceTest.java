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

// TODO: re-add project imports for: Version, ReglissList, OptionsFile, OptionsFileDto, OptionsFileMapper

@RunWith(MockitoJUnitRunner.Silent.class)
public class OptionsFileImportServiceTest {

    @InjectMocks
    private OptionsFileImportService service;

    @Mock
    private OptionsFileMapper optionsFileMapper;
    @Mock
    private SixBatchImportRunner runner;
    @Mock
    private SixJdbcBulkWriter writer;
    @Mock
    private Version version;
    @Mock
    private ReglissList list;

    @SuppressWarnings("unchecked")
    private SixBatchImportRunner.RecordType<OptionsFileDto, OptionsFile> captureType(File expectedFile) throws Exception {
        ArgumentCaptor<SixBatchImportRunner.RecordType> type = ArgumentCaptor.forClass(SixBatchImportRunner.RecordType.class);
        verify(runner).run(type.capture(), eq(expectedFile), eq(version), eq(list), eq(42L));
        return type.getValue();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void fileIsStreamedWithTheRecordTypeOfoptionsFiles() throws Exception {
        File xml = new File("transformed.xml");
        service.streamAndPersist(xml, version, list, 42L);

        SixBatchImportRunner.RecordType<OptionsFileDto, OptionsFile> type = captureType(xml);
        assertEquals("options", ReflectionTestUtils.getField(type, "label"));
        assertEquals("OptionsFile", ReflectionTestUtils.getField(type, "recordElement"));
        assertEquals(OptionsFileImportService.RECORD_ELEMENT, ReflectionTestUtils.getField(type, "recordElement"));
        assertSame(OptionsFileDto.class, ReflectionTestUtils.getField(type, "dtoClass"));

        // DTO -> entity: the mapper of the service
        OptionsFileDto dto = mock(OptionsFileDto.class);
        OptionsFile entity = mock(OptionsFile.class);
        when(optionsFileMapper.mapperStructuredFile(dto)).thenReturn(entity);
        assertSame(entity, ((Function<OptionsFileDto, OptionsFile>) ReflectionTestUtils.getField(type, "mapper")).apply(dto));

        // batch -> JDBC writer with the list id, version id and the SIX_TARGET foreign key column
        when(list.getId()).thenReturn(11L);
        when(version.getId()).thenReturn(22L);
        List<OptionsFile> batch = Collections.singletonList(entity);
        ((SixBatchImportRunner.BatchWriter<OptionsFile>) ReflectionTestUtils.getField(type, "writer")).write(batch, version, list);
        verify(writer).insertBatch(eq(batch), eq(11L), eq(22L), any(Function.class), eq("SIX_OPT_ID"));
    }
}
