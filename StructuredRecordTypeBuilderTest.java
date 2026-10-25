package com.bnpp.regliss.importer.six.extractor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.beans.BeanWrapperImpl;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// TODO: re-add project imports for: FilteredStructuredFile, FilteredSixTarget, ReglissList, RecordType, DocumentType,
// CodeType, ImportedFileRepository

@RunWith(MockitoJUnitRunner.Silent.class)
public class StructuredRecordTypeBuilderTest {

    /** Same identifiers as the instrument records; the structured entity names one field franceEuroClear. */
    private static final Map<String, String> IDENTIFIERS = new LinkedHashMap<>();

    static {
        InstrumentRecordTypeBuilderTest.IDENTIFIERS.forEach((field, info) ->
                IDENTIFIERS.put("franceEuroclear".equals(field) ? "franceEuroClear" : field, info));
    }

    @InjectMocks
    private StructuredRecordTypeBuilder builder;

    @Mock
    private ImportedFileRepository importedFileRepository;
    @Mock
    private SixRecordStartDates sixRecordStartDates;

    private final ReglissList list = mock(ReglissList.class);

    @Before
    public void setUp() {
        when(sixRecordStartDates.ofLastVersion(list)).thenReturn(SixRecordStartDates.Lookup.of(
                Collections.singletonList(new Object[]{"HCH1", "2025-05-05"})));
    }

    private static FilteredSixTarget target(String regime, String legalBasis, String target, String reason) {
        return InstrumentRecordTypeBuilderTest.target(regime, legalBasis, target, reason, null);
    }

    private RecordType build(FilteredStructuredFile file) {
        List<RecordType> records = builder.filteredStructureFileToRecordsTypeConverter(Collections.singletonList(file), list, "2026-10-04");
        assertEquals(1, records.size());
        return records.get(0);
    }

    @Test
    public void record_generalInformation_notesInMacroOrder() {
        FilteredStructuredFile file = new FilteredStructuredFile();
        file.setHostCh("HCH1");
        file.setHostIssuerShortname("ISSUER");
        file.setHostIsin("XS9");
        file.setHostGk("GK");
        file.setConfidenceLevel("80");
        file.setIndicativeIssueDate("2020");
        file.setInstrumentType("STRUCT");
        file.setDenominationCurrency("USD");
        file.setMaturityDate("2031");
        file.setActiveFlag("Active");
        file.setIssueDate("2021");
        file.setDescription("D");
        file.setUnderlyingCh("UCH");
        file.setUnderlyingIsin("UISIN");
        file.setUnderlyingGk("UGK");
        file.setUnderlyingIssuerShortname("USN");
        file.setFisn("FISN2");
        file.setSixTargets(Arrays.asList(target("UK", "SAMLA", "T1", "new"), target("eu", "Reg", "T2", ""),
                target("UK", "SAMLA", "T1", null), target("US", null, "T3", "upd")));

        RecordType r = build(file);

        assertEquals("HCH1", r.getExternalReference());
        assertEquals("2025-05-05", r.getApplicationDate());
        assertNull(r.getDeletionDate());
        assertEquals("ISSUER", r.getGeneralInformation().getFullName());
        assertEquals("", r.getGeneralInformation().getComments());
        assertEquals("UK eu", r.getGeneralInformation().getSearchCode());
        assertEquals("ISIN related characteristics : host_gk : GK; confidence_level : 80; indicative_issue_date : 2020;"
                + " instrument_type : STRUCT; denomination_currency : USD; maturity_date : 2031; active_flag : Active;"
                + " issue_date : 2021; description : D; underlying_ch : UCH; underlying_isin : UISIN; underlying_gk : UGK;"
                + " underlying_issuer_shortname : USN; FISN Instrument short name : FISN2; reason_for_change : new - upd",
                r.getGeneralInformation().getNotes());
        assertEquals(Arrays.asList("eu - Reg|T2", "UK - SAMLA|T1"), r.getGeneralInformation().getPrograms().getProgram().stream()
                .map(p -> p.getLabel() + "|" + p.getDescription()).collect(Collectors.toList()));
        assertEquals(Collections.singletonList("ISIN:XS9"), r.getExtraEntityRecordDetail().getCodes().getCode().stream()
                .map(c -> c.getType() + ":" + c.getValue()).collect(Collectors.toList()));
    }

    @Test
    public void mergedTextTargets_andMissingStartDate() {
        FilteredStructuredFile file = new FilteredStructuredFile();
        file.setHostCh("OTHER");
        file.setSixTargets(Collections.singletonList(target(null, "LB", "T", "own")));       // no regime: no program
        file.setMergedTextTargets(Collections.singletonList(target(null, null, null, "merged")));
        RecordType r = build(file);
        assertEquals("2026-10-04", r.getApplicationDate());
        assertEquals("ISIN related characteristics : reason_for_change : merged", r.getGeneralInformation().getNotes());
        assertTrue(r.getGeneralInformation().getPrograms().getProgram().isEmpty());
        assertEquals("", r.getGeneralInformation().getSearchCode());
    }

    /** Targets with a legal basis, one without regime: skipped in the search code (no NullPointerException in the sort). */
    @Test
    public void searchCode_targetWithoutRegimeAmongOthers_skipped() {
        FilteredStructuredFile file = new FilteredStructuredFile();
        file.setSixTargets(Arrays.asList(target("UK", "L", "T1", null), target(null, "L2", "T2", null),
                target("EU", "L3", "T3", null)));
        RecordType r = build(file);
        assertEquals("EU UK", r.getGeneralInformation().getSearchCode());
        assertEquals(2, r.getGeneralInformation().getPrograms().getProgram().size());
    }

    @Test
    public void emptyFile_minimalRecord() {
        FilteredStructuredFile file = new FilteredStructuredFile();
        RecordType r = build(file);
        assertEquals("ISIN related characteristics ", r.getGeneralInformation().getNotes());
        assertTrue(r.getExtraEntityRecordDetail().getCodes().getCode().isEmpty());
        assertTrue(r.getExtraEntityRecordDetail().getIdentityDocuments().getDocument().isEmpty());
    }

    @Test
    public void identityDocuments_oneValue_orSeveralSeparatedByDash() {
        FilteredStructuredFile single = new FilteredStructuredFile();
        FilteredStructuredFile several = new FilteredStructuredFile();
        BeanWrapperImpl singleBean = new BeanWrapperImpl(single);
        BeanWrapperImpl severalBean = new BeanWrapperImpl(several);
        IDENTIFIERS.keySet().forEach(field -> {
            singleBean.setPropertyValue(field, "V-" + field);
            severalBean.setPropertyValue(field, "A " + field + " - B " + field);
        });
        single.setCusip("C1");
        several.setCusip("C1 - C2");

        List<DocumentType> one = build(single).getExtraEntityRecordDetail().getIdentityDocuments().getDocument();
        List<DocumentType> two = build(several).getExtraEntityRecordDetail().getIdentityDocuments().getDocument();

        int fields = IDENTIFIERS.size();
        assertEquals(fields, one.size());
        assertEquals(2 * fields, two.size());
        assertEquals(IDENTIFIERS.values().stream().map(v -> "Instrument Identifier " + v)
                .collect(Collectors.toList()), one.stream().map(DocumentType::getAdditionalInfo).collect(Collectors.toList()));
        assertEquals(StructuredRecordTypeBuilder.OTHERS, two.get(0).getType());
        assertEquals("A sedol", two.get(0).getNumber());
        assertEquals("B sedol", two.get(1).getNumber());

        assertEquals(Arrays.asList("C1", "C2"), build(several).getExtraEntityRecordDetail().getCodes().getCode().stream()
                .map(CodeType::getValue).collect(Collectors.toList()));
    }
}
