package com.bnpp.regliss.importer.six.extractor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// TODO: re-add project imports for: ListType, RecordType, ReglissList, Version, FilteredFileBundle, ImportedFile,
// ImportedFileRepository, VersionRepository, Scope

@RunWith(MockitoJUnitRunner.Silent.class)
public class ListTypeBuilderTest {

    @InjectMocks
    private ListTypeBuilder builder;

    @Mock
    private InstrumentRecordTypeBuilder instrumentRecordTypeBuilder;
    @Mock
    private StructuredRecordTypeBuilder structuredRecordTypeBuilder;
    @Mock
    private ImportedFileRepository importedFileRepository;
    @Mock
    private VersionRepository versionRepository;

    private ReglissList list;
    private final FilteredFileBundle bundle = new FilteredFileBundle();

    @Before
    public void setUp() {
        list = mock(ReglissList.class);
        when(list.getScope()).thenReturn(Scope.values()[0]);
        when(list.getShortName()).thenReturn("UK SANCTIONS");
        when(list.getLastVersion()).thenReturn(Optional.empty());
        importedFile(100L, "GSM_1_202_EU_INSTR_FULL_20261004_101500.xml");
    }

    private void importedFile(Long versionId, String name) {
        ImportedFile file = mock(ImportedFile.class);
        when(file.getOriginalFileName()).thenReturn(name);
        when(importedFileRepository.findByVersionId(versionId)).thenReturn(file);
    }

    private static RecordType record(String externalReference) {
        RecordType r = new RecordType();
        r.setExternalReference(externalReference);
        return r;
    }

    private void records(List<RecordType> instruments, List<RecordType> structured) {
        when(instrumentRecordTypeBuilder.filteredInstrumentFileToRecordsTypeConverter(eq(bundle.getInstrumentFiles()), eq(list),
                anyString())).thenReturn(instruments);
        when(structuredRecordTypeBuilder.filteredStructureFileToRecordsTypeConverter(eq(bundle.getStructuredFiles()), eq(list),
                anyString())).thenReturn(structured);
    }

    @Test
    public void converter_givenVersion_firstExport() {
        RecordType i = record("I1");
        RecordType s = record("S1");
        records(Collections.singletonList(i), Collections.singletonList(s));

        ListType type = builder.filteredSixRawToListTypeConverter(list, bundle, 100L);

        assertEquals("2026-10-04", type.getApplicationDate());
        assertEquals(Scope.values()[0].name(), type.getJuridictionBase());
        assertEquals("UK SANCTIONS", type.getLabel());
        assertEquals("1", type.getSourceVersion());                       // no version yet
        assertEquals("FULL", type.getType());
        assertEquals(Arrays.asList(i, s), type.getRecords().getRecordObj());
        verify(versionRepository, never()).getLatestVersionOfInstruments();
    }

    @Test
    public void converter_latestInstrumentVersion_nextSourceVersion() {
        Version latest = mock(Version.class);
        when(latest.getId()).thenReturn(200L);
        when(versionRepository.getLatestVersionOfInstruments()).thenReturn(latest);
        importedFile(200L, "gsm_instr_20250131_235959.XML");
        Version last = mock(Version.class);
        when(last.getVersionNumber()).thenReturn(6);
        when(list.getLastVersion()).thenReturn(Optional.of(last));
        records(Collections.emptyList(), Collections.emptyList());

        ListType type = builder.filteredSixRawToListTypeConverter(list, bundle);

        assertEquals("2025-01-31", type.getApplicationDate());
        assertEquals("7", type.getSourceVersion());
    }

    @Test
    public void generation_lastEntryWins_uniqueReferences() {
        records(Arrays.asList(record("A"), record("B")), Collections.emptyList());
        Map<ReglissList, FilteredFileBundle> bundles = new LinkedHashMap<>();
        bundles.put(list, bundle);

        ListType type = builder.generationOfListTypes(bundles, "951", 100L);

        assertEquals(2, type.getRecords().getRecordObj().size());
    }

    @Test
    public void generation_withoutVersion_usesLatestInstrumentVersion() {
        Version latest = mock(Version.class);
        when(latest.getId()).thenReturn(100L);
        when(versionRepository.getLatestVersionOfInstruments()).thenReturn(latest);
        records(Collections.singletonList(record("A")), Collections.emptyList());
        Map<ReglissList, FilteredFileBundle> bundles = Collections.singletonMap(list, bundle);

        ListType type = builder.generationOfListTypes(bundles, "951");

        assertSame(list.getShortName(), type.getLabel());
    }

    @Test
    public void generation_duplicatedExternalReference_fails() {
        records(Collections.singletonList(record("A")), Collections.singletonList(record("A")));
        Map<ReglissList, FilteredFileBundle> bundles = Collections.singletonMap(list, bundle);
        try {
            builder.generationOfListTypes(bundles, "951", 100L);
            fail();
        } catch (IllegalStateException e) {
            assertEquals("Duplicate external reference found A for list ref 951", e.getMessage());
        }
    }

    /** No output list in the map: the empty ListType has no records, so the duplicate check fails (existing behaviour). */
    @Test
    public void generation_noList_failsOnTheMissingRecords() {
        Map<ReglissList, FilteredFileBundle> none = Collections.emptyMap();
        try {
            builder.generationOfListTypes(none, "951", 100L);
            fail();
        } catch (NullPointerException e) {
            verify(instrumentRecordTypeBuilder, never()).filteredInstrumentFileToRecordsTypeConverter(any(), any(), any());
        }
    }

    /** Instrument file name without a _yyyyMMdd_ date: no application date can be built (existing behaviour). */
    @Test
    public void fileNameWithoutDate_fails() {
        importedFile(300L, "GSM_INSTR_FULL.xml");
        try {
            builder.filteredSixRawToListTypeConverter(list, bundle, 300L);
            fail();
        } catch (NullPointerException e) {
            verify(instrumentRecordTypeBuilder, never()).filteredInstrumentFileToRecordsTypeConverter(any(), any(), any());
        }
    }
}
