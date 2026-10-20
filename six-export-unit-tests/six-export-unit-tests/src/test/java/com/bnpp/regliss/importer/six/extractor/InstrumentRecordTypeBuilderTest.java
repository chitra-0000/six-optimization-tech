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

// TODO: re-add project imports for: FilteredInstrumentFile, FilteredSixTarget, ReglissList, RecordType, DocumentType,
// ProgramType, CodeType, ImportedFileRepository

@RunWith(MockitoJUnitRunner.Silent.class)
public class InstrumentRecordTypeBuilderTest {

    /** Identifier field of FilteredInstrumentFile -> additional info of its identity document. */
    static final Map<String, String> IDENTIFIERS = new LinkedHashMap<>();

    static {
        IDENTIFIERS.put("sedol", "SEDOL");
        IDENTIFIERS.put("cins", "CINS");
        IDENTIFIERS.put("austrian", "AUSTRIAN");
        IDENTIFIERS.put("belgian", "BELGIAN");
        IDENTIFIERS.put("canadian", "CANADIAN");
        IDENTIFIERS.put("german", "GERMAN");
        IDENTIFIERS.put("denmark", "DENMARK");
        IDENTIFIERS.put("franceRga", "FRANCE_RGA");
        IDENTIFIERS.put("franceEuroclear", "FRANCE_EUROCLEAR");
        IDENTIFIERS.put("italian", "ITALIAN");
        IDENTIFIERS.put("japaneseCurrent", "JAPANESE_CURRENT");
        IDENTIFIERS.put("japaneseNew", "JAPANESE_NEW");
        IDENTIFIERS.put("luxembourg", "LUXEMBOURG");
        IDENTIFIERS.put("netherland", "NETHERLAND");
        IDENTIFIERS.put("norwegian", "NORWEGIAN");
        IDENTIFIERS.put("swedish", "SWEDISH");
        IDENTIFIERS.put("xsIntNumber", "XS_INT_NUMBER");
        IDENTIFIERS.put("portugal", "PORTUGAL");
        IDENTIFIERS.put("southKorea", "SOUTH_KOREA");
        IDENTIFIERS.put("hongKong", "HONG_KONG");
        IDENTIFIERS.put("figiGlobalId", "BLOOMBERG_ID");
    }

    @InjectMocks
    private InstrumentRecordTypeBuilder builder;

    @Mock
    private ImportedFileRepository importedFileRepository;
    @Mock
    private SixRecordStartDates sixRecordStartDates;

    private final ReglissList list = mock(ReglissList.class);

    @Before
    public void setUp() {
        when(sixRecordStartDates.ofLastVersion(list)).thenReturn(SixRecordStartDates.Lookup.of(
                Collections.singletonList(new Object[]{"CH1", "2025-05-05"})));
    }

    static FilteredSixTarget target(String regime, String legalBasis, String target, String reason, String rationale) {
        FilteredSixTarget t = new FilteredSixTarget();
        t.setRegime(regime);
        t.setLegalBasis(legalBasis);
        t.setTarget(target);
        t.setReasonForChange(reason);
        t.setSanctionsRationale(rationale);
        return t;
    }

    private RecordType build(FilteredInstrumentFile file) {
        List<RecordType> records = builder.filteredInstrumentFileToRecordsTypeConverter(Collections.singletonList(file), list, "2026-10-04");
        assertEquals(1, records.size());
        return records.get(0);
    }

    @Test
    public void record_generalInformation_notesInMacroOrder() {
        FilteredInstrumentFile file = new FilteredInstrumentFile();
        file.setChValor("CH1");
        file.setNameDirectIssuer("ACME");
        file.setIsin("XS1");
        file.setLinkEntity("LE");
        file.setLinkCsid("CS");
        file.setSanctionedParentEntity("PARENT");
        file.setConfidenceLevel("90");
        file.setInstrName("BOND");
        file.setIndicativeIssueDate("2020");
        file.setInstrumentType("DEBT");
        file.setSanctionsRelevantAssetClass("A");
        file.setMainInstrument("M");
        file.setEquityTypeOfIssuance("E");
        file.setDenominationCurrency("EUR");
        file.setMaturityDate("2030");
        file.setDebtLifetimeInDays("10");
        file.setActiveFlag("Active");
        file.setIssueDate("2021");
        file.setCapitalChangeDate("2022");
        file.setDescription("D");
        file.setUnderlyingCh("UCH");
        file.setUnderlyingIsin("UISIN");
        file.setUnderlyingGk("UGK");
        file.setUnderlyingIssuerShortname("USN");
        file.setFisn("FISN1");
        file.setSixTargets(Arrays.asList(
                target("UK", "SAMLA", "T1", "new", "r1"),
                target("eu", "Reg 833", "T2", "", "r1"),
                target("UK", "SAMLA", "T1", "new", null),                    // same program: once
                target("US", null, "T3", null, ""),                            // no legal basis: no program, no search code
                target("XX", null, "T4", "upd", "r2")));                       // no legal basis: no program

        RecordType r = build(file);

        assertEquals("CH1", r.getExternalReference());
        assertEquals("2025-05-05", r.getApplicationDate());                    // start date of the last version
        assertNull(r.getDeletionDate());
        assertEquals("ACME", r.getGeneralInformation().getFullName());
        assertEquals("Entity", r.getGeneralInformation().getType());
        assertEquals("UK eu", r.getGeneralInformation().getSearchCode());
        assertEquals("sanctions_rationale : r1 | r2", r.getGeneralInformation().getComments());
        assertEquals("ISIN related characteristics : link_entity : LE; link_csid : CS; sanctioned_parent_entity : PARENT;"
                + " confidence_level : 90; instr_name : BOND; indicative_issue_date : 2020; instrument_type : DEBT;"
                + " sanctions_relevant_asset_class : A; main_instrument : M; equity_type_of_issuance : E;"
                + " denomination_currency : EUR; maturity_date : 2030; debt_lifetime_in_days : 10; active_flag : Active;"
                + " issue_date : 2021; capital_change_date : 2022; description : D; underlying_ch : UCH; underlying_isin : UISIN;"
                + " underlying_gk : UGK; underlying_issuer_shortname : USN; FISN Instrument short name : FISN1;"
                + " reason_for_change : new - upd", r.getGeneralInformation().getNotes());
        List<String> programs = r.getGeneralInformation().getPrograms().getProgram().stream()
                .map(p -> p.getLabel() + "|" + p.getDescription() + "|" + p.getType()).collect(Collectors.toList());
        assertEquals(Arrays.asList("eu - Reg 833|T2|BNPP Input", "UK - SAMLA|T1|BNPP Input"), programs);
        assertEquals(Collections.singletonList("XS1"), r.getExtraEntityRecordDetail().getCodes().getCode().stream()
                .map(c -> c.getType().equals("ISIN") ? c.getValue() : "").collect(Collectors.toList()));
        assertEquals("", r.getExtraOtherRecordDetail().getCategory());
    }

    @Test
    public void searchCode_regimesWithLegalBasis_sortedOnce() {
        FilteredInstrumentFile file = new FilteredInstrumentFile();
        file.setSixTargets(Arrays.asList(target("UK", "L", "T", null, null), target("EU", "L", "T", null, null),
                target("UK", "L2", "T", null, null), target("US", null, "T", null, null)));
        assertEquals("EU UK", build(file).getGeneralInformation().getSearchCode());
    }

    /** One target with a legal basis but no regime: no program (its search code is the text "null", existing behaviour). */
    @Test
    public void targetWithoutRegime_noProgram() {
        FilteredInstrumentFile file = new FilteredInstrumentFile();
        file.setSixTargets(Collections.singletonList(target(null, "LB", "T", null, null)));
        RecordType r = build(file);
        assertTrue(r.getGeneralInformation().getPrograms().getProgram().isEmpty());
        assertEquals("null", r.getGeneralInformation().getSearchCode());
    }

    @Test
    public void mergedTextTargets_usedForRationaleAndReason_notForPrograms() {
        FilteredInstrumentFile file = new FilteredInstrumentFile();
        file.setChValor("UNKNOWN");
        file.setSixTargets(Collections.singletonList(target("UK", "SAMLA", "T1", "own", "own rationale")));
        file.setMergedTextTargets(Arrays.asList(target(null, null, null, "merged", "merged rationale"),
                target(null, null, null, "merged", "merged rationale")));

        RecordType r = build(file);

        assertEquals("2026-10-04", r.getApplicationDate());                    // not in the last version: file date
        assertEquals("sanctions_rationale : merged rationale", r.getGeneralInformation().getComments());
        assertEquals("ISIN related characteristics : reason_for_change : merged", r.getGeneralInformation().getNotes());
        assertEquals(1, r.getGeneralInformation().getPrograms().getProgram().size());
    }

    @Test
    public void emptyFile_minimalRecord() {
        FilteredInstrumentFile file = new FilteredInstrumentFile();
        file.setIsin(" ");
        RecordType r = build(file);
        assertEquals("ISIN related characteristics ", r.getGeneralInformation().getNotes());
        assertEquals("sanctions_rationale : ", r.getGeneralInformation().getComments());
        assertEquals("", r.getGeneralInformation().getSearchCode());
        assertTrue(r.getExtraEntityRecordDetail().getCodes().getCode().isEmpty());
        assertTrue(r.getExtraEntityRecordDetail().getIdentityDocuments().getDocument().isEmpty());
    }

    @Test
    public void identityDocuments_oneValue_orSeveralSeparatedByDash() {
        FilteredInstrumentFile single = new FilteredInstrumentFile();
        FilteredInstrumentFile several = new FilteredInstrumentFile();
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

        assertEquals(IDENTIFIERS.size(), one.size());
        assertEquals(2 * IDENTIFIERS.size(), two.size());
        List<String> infos = one.stream().map(DocumentType::getAdditionalInfo).collect(Collectors.toList());
        assertEquals(IDENTIFIERS.values().stream().map(v -> "Instrument Identifier " + v).collect(Collectors.toList()), infos);
        DocumentType first = two.get(0);
        assertEquals(InstrumentRecordTypeBuilder.OTHERS, first.getType());
        assertEquals("A sedol", first.getNumber());
        assertEquals("B sedol", two.get(1).getNumber());
        assertEquals("", first.getCountry());

        assertEquals(Collections.singletonList("C1"), build(single).getExtraEntityRecordDetail().getCodes().getCode().stream()
                .map(CodeType::getValue).collect(Collectors.toList()));
        assertEquals(Arrays.asList("C1", "C2"), build(several).getExtraEntityRecordDetail().getCodes().getCode().stream()
                .map(CodeType::getValue).collect(Collectors.toList()));
    }
}
