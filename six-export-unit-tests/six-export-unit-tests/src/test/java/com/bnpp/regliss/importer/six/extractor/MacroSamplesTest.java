package com.bnpp.regliss.importer.six.extractor;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// TODO: re-add project imports for: ReglissList, FilteredInstrumentFile, FilteredStructuredFile, FilteredSixTarget,
// RecordType, GeneralInformationType, DocumentType

/** The two samples given by the business (macro output files), list 225 (UK). */
public class MacroSamplesTest {

    private final SixIsinRecordMerger merger = new SixIsinRecordMerger();
    private final InstrumentRecordTypeBuilder instrumentBuilder = new InstrumentRecordTypeBuilder();
    private final StructuredRecordTypeBuilder structuredBuilder = new StructuredRecordTypeBuilder();
    private final ReglissList list = mock(ReglissList.class);

    @Before
    public void setUp() {
        SixRecordStartDates noStartDates = mock(SixRecordStartDates.class);
        when(noStartDates.ofLastVersion(any())).thenReturn(SixRecordStartDates.Lookup.EMPTY);
        ReflectionTestUtils.setField(instrumentBuilder, "sixRecordStartDates", noStartDates);
        ReflectionTestUtils.setField(structuredBuilder, "sixRecordStartDates", noStartDates);
        when(list.getReference()).thenReturn("225");
    }

    // ------------------------------------------------------------------------------------------ sample 1

    static final String R1_RATIONALE = "The security ISIN RU000A0JSXA9 has been issued by the entity GK 569355 [Reserved trust company JSC] which is domiciled in the Russian Federation.\n"
            + "Since the issuance took place after the sanctions effective date, the security is considered sanctioned under the provisions of US regulator(s).\n"
            + "The security is In default and thus the status could change in the future.";
    static final String R2_RATIONALE = "The security ISIN RU000A0JSXA9 is ultimately cleared or deposited at the entity GK 221344 [National Settlement Depository] which is linked to\n"
            + "PUBLIC JOINT-STOCK COMPANY MOSCOW EXCHANGE MICEX-RTS. The security is sanctioned under the provisions of EU US UK regulator(s). The security is In default.";

    static FilteredInstrumentFile sample1Row(String parent) {
        FilteredInstrumentFile f = new FilteredInstrumentFile();
        f.setIsin("RU000A0JSXA9");
        f.setChValor("19433636");
        f.setSedol("BKF2K77");
        f.setCins("X7346VAC7");
        f.setFigiGlobalId("BBG003C71Z41");
        f.setLinkEntity("569355");
        f.setNameDirectIssuer("Reserved trust company JSC");
        f.setSanctionedParentEntity(parent);
        f.setConfidenceLevel("100");
        f.setInstrName("RTC 27 4 Bds Bonds 2012-18.08.27 Series 4 Reset Put Bonds -In      Default-");
        f.setFisn("REZERV TRAST KO/VOLA VARI B BR CORP");
        f.setIndicativeIssueDate("20120905");
        f.setInstrumentType("Debt");
        f.setSanctionsRelevantAssetClass("Debt");
        f.setDenominationCurrency("RUB");
        f.setMaturityDate("20270818");
        f.setActiveFlag("In default");
        f.setIssueDate("20120905");
        return f;
    }

    static FilteredSixTarget target(String target, String regime, String legalBasis, String rationale) {
        FilteredSixTarget t = new FilteredSixTarget();
        t.setTarget(target);
        t.setRegime(regime);
        t.setLegalBasis(legalBasis);
        t.setSanctioned("Yes");
        t.setSanctionsRationale(rationale);
        return t;
    }

    static final String[] OTHER_REGIMES = {"UN", "HK", "CH", "CA", "AU", "SG", "NL", "JP", "NZ", "REG2"};

    static void addNotEnforced(FilteredInstrumentFile f, String targetName, String rationale) {
        for (String regime : OTHER_REGIMES) {
            f.addSixTarget(target(targetName, regime, null, rationale));
        }
    }

    @Test
    public void sample1_twoInstrumentRows_onlySecondPassesUk() {
        // filtered row (passes list 225): record 2 with its UK target only
        FilteredInstrumentFile filtered = sample1Row("PUBLIC JOINT-STOCK COMPANY MOSCOW EXCHANGE MICEX-RTS");
        filtered.addSixTarget(target("RUSSIA_AND_UKRAINE/RUSSIA", "UK", "The Russia (Sanctions) (EU Exit) Regulations 2019", R2_RATIONALE));

        // raw rows meeting the base conditions (raw order: record 1, record 2), all their sanctioned targets
        FilteredInstrumentFile raw1 = sample1Row("RUSSIA_CONNECTED");
        raw1.addSixTarget(target("RUSSIA_DOMICILED", "US_NON_SDN", "RUSSIA-EO14071 - 01/01/1900", R1_RATIONALE));
        addNotEnforced(raw1, "RUSSIA_DOMICILED", R1_RATIONALE);
        FilteredInstrumentFile raw2 = sample1Row("PUBLIC JOINT-STOCK COMPANY MOSCOW EXCHANGE MICEX-RTS");
        raw2.addSixTarget(target("RUSSIA_AND_UKRAINE/RUSSIA", "EU", "2022/878 (OJ L153)", R2_RATIONALE));
        raw2.addSixTarget(target("RUSSIA_AND_UKRAINE/RUSSIA", "US_SDN", "RUSSIA-EO14024-UKRAINE-EO13662", R2_RATIONALE));
        raw2.addSixTarget(target("RUSSIA_AND_UKRAINE/RUSSIA", "UK", "The Russia (Sanctions) (EU Exit) Regulations 2019", R2_RATIONALE));
        addNotEnforced(raw2, "RUSSIA_AND_UKRAINE/RUSSIA", R2_RATIONALE);

        Map<String, List<FilteredInstrumentFile>> rawInstruments = new HashMap<>();
        rawInstruments.put("RU000A0JSXA9", Arrays.asList(raw1, raw2));
        SixIsinMergeContent content = new SixIsinMergeContent(Collections.emptySet(), rawInstruments, Collections.emptyMap());

        SixIsinRecordMerger.Result result = merger.merge(Collections.singletonList(filtered), Collections.emptyList(), content, "225");
        assertEquals(1, result.getInstruments().size());
        assertEquals(0, result.getStructured().size());
        RecordType rec = instrumentBuilder.filteredInstrumentFileToRecordsTypeConverter(result.getInstruments(), list, "2022-06-23").get(0);

        GeneralInformationType gi = rec.getGeneralInformation();
        assertEquals("19433636", rec.getExternalReference());
        assertEquals("Reserved trust company JSC", gi.getFullName());
        assertEquals("UK", gi.getSearchCode());
        // macro output, line breaks of the file display removed
        assertEquals(oneLine("ISIN related characteristics : link_entity : 569355; sanctioned_parent_entity : PUBLIC JOINT-STOCK COMPANY MOSCOW EXCHANGE MICEX-RTS - RUSSIA_CONNECTED;\n"
                + "confidence_level : 100; instr_name : RTC 27 4 Bds Bonds 2012-18.08.27 Series 4 Reset Put Bonds -In      Default-; indicative_issue_date : 2012-09-05; instrument_type : Debt;\n"
                + "sanctions_relevant_asset_class : Debt; denomination_currency : RUB; maturity_date : 2027-08-18; active_flag : In default; issue_date : 2012-09-05;\n"
                + "FISN Instrument short name : REZERV TRAST KO/VOLA VARI B BR CORP"), gi.getNotes());
        assertEquals(words("sanctions_rationale : The security ISIN RU000A0JSXA9 is ultimately cleared or deposited at the entity GK 221344 [National Settlement Depository] which is linked\n"
                + "to PUBLIC JOINT-STOCK COMPANY MOSCOW EXCHANGE MICEX-RTS. The security is sanctioned under the provisions of EU US UK regulator(s). The security is In default. |\n"
                + "The security ISIN RU000A0JSXA9 has been issued by the entity GK 569355 [Reserved trust company JSC] which is domiciled in the Russian Federation.\n"
                + "Since the issuance took place after the sanctions effective date, the security is considered sanctioned under the provisions of US regulator(s).\n"
                + "The security is In default and thus the status could change in the future."), words(gi.getComments()));
        assertEquals(1, gi.getPrograms().getProgram().size());
        ProgramType program = gi.getPrograms().getProgram().get(0);
        assertEquals("UK - The Russia (Sanctions) (EU Exit) Regulations 2019", program.getLabel());
        assertEquals("BNPP Input", program.getType());
        assertEquals("RUSSIA_AND_UKRAINE/RUSSIA", program.getDescription());
        assertEquals(Arrays.asList("Instrument Identifier SEDOL=BKF2K77", "Instrument Identifier CINS=X7346VAC7",
                "Instrument Identifier BLOOMBERG_ID=BBG003C71Z41"), documents(rec));
        assertEquals(Collections.singletonList("ISIN=RU000A0JSXA9"), codes(rec));
    }

    // ------------------------------------------------------------------------------------------ sample 2

    static final String I_RATIONALE = "The security ISIN XS2304902443 has been issued by the entity GK 864011 [Ozon Holdings PLC] which predominantly derives its revenue\n"
            + "from the Russian Federation. Since the issuance took place after the sanctions effective date, the security is considered sanctioned under the provisions of\n"
            + "US regulator(s). The security is Inactive and thus the status IS FINAL.";

    static FilteredStructuredFile ozonStructured(String underlyingGk) {
        FilteredStructuredFile s = new FilteredStructuredFile();
        s.setHostCh("110109819");
        s.setHostIsin("XS2304902443");
        s.setSedol("BLKH0B8");
        s.setCins("M7622KAA2");
        s.setGerman("A3KL8V");
        s.setXsIntNumber("230490244");
        s.setFigiGlobalId("BBG00Z9BQRZ2");
        s.setHostGk("864011");
        s.setHostIssuerShortname("Ozon Hldgs");
        s.setUnderlyingCh("58353386");
        s.setUnderlyingIsin("US69269L1044");
        s.setUnderlyingGk(underlyingGk);
        s.setUnderlyingIssuerShortname("Ozon Hldgs");
        s.setDescription("1.875 Ozon 26 CVB-S 1.875 % Convertible Bonds 2021-24.02.26 Reg S");
        s.setFisn("OZON HOLDINGS P/1.875 FM WC");
        s.setIndicativeIssueDate("20210224");
        s.setInstrumentType("Debt");
        s.setDenominationCurrency("USD");
        s.setMaturityDate("20260224");
        s.setActiveFlag("Inactive");
        s.setIssueDate("20210224");
        s.setConfidenceLevel("100");
        return s;
    }

    static FilteredInstrumentFile ozonInstrument() {
        FilteredInstrumentFile f = new FilteredInstrumentFile();
        f.setIsin("XS2304902443");
        f.setChValor("110109819");
        f.setSedol("BLKH0B8");
        f.setCins("M7622KAA2");
        f.setGerman("A3KL8V");
        f.setXsIntNumber("230490244");
        f.setFigiGlobalId("BBG00Z9BQRZ2");
        f.setLinkEntity("864011");
        f.setNameDirectIssuer("Ozon Holdings PLC");
        f.setSanctionedParentEntity("Non-Russian-Holding");
        f.setConfidenceLevel("100");
        f.setInstrName("1.875 Ozon 26 CVB-S 1.875 % Convertible Bonds 2021-24.02.26 Reg S");
        f.setFisn("OZON HOLDINGS P/1.875 FM WC");
        f.setIndicativeIssueDate("20210224");
        f.setInstrumentType("Debt");
        f.setSanctionsRelevantAssetClass("Debt");
        f.setDenominationCurrency("USD");
        f.setMaturityDate("20260224");
        f.setActiveFlag("Inactive");
        f.setIssueDate("20210224");
        f.addSixTarget(target("RUSSIA_DOMICILED", "US_NON_SDN", "EO14071/FAQ1055 - 01/01/1900", I_RATIONALE));
        addNotEnforced(f, "RUSSIA_DOMICILED", I_RATIONALE);
        return f;
    }

    @Test
    public void sample2_inactiveInstrumentFailsUk_structuredPasses_oneInstrumentRecord() {
        // filtered: structured 1 with its UK target only (structured targets carry no rationale)
        FilteredStructuredFile filtered = ozonStructured("221344");
        filtered.addSixTarget(target("RUSSIA_AND_UKRAINE/RUSSIA", "UK", "The Russia (Sanctions) (EU Exit) Regulations 2019", null));

        FilteredStructuredFile raw1 = ozonStructured("221344");
        raw1.addSixTarget(target("RUSSIA_AND_UKRAINE/RUSSIA", "EU", "2022/878 (OJ L153)", null));
        raw1.addSixTarget(target("RUSSIA_AND_UKRAINE/RUSSIA", "UK", "The Russia (Sanctions) (EU Exit) Regulations 2019", null));
        FilteredStructuredFile raw2 = ozonStructured("864011");
        raw2.addSixTarget(target("RUSSIA_DOMICILED", "US_NON_SDN", "EO14071/FAQ1055 - 01/01/1900", null));

        Map<String, List<FilteredInstrumentFile>> rawInstruments = new HashMap<>();
        rawInstruments.put("XS2304902443", Collections.singletonList(ozonInstrument()));
        Map<String, List<FilteredStructuredFile>> rawStructured = new HashMap<>();
        rawStructured.put("XS2304902443", Arrays.asList(raw1, raw2));
        // the instrument row is Inactive: not an "active instrument", its structured rows are used
        SixIsinMergeContent content = new SixIsinMergeContent(Collections.emptySet(), rawInstruments, rawStructured);

        SixIsinRecordMerger.Result result = merger.merge(Collections.emptyList(), Collections.singletonList(filtered), content, "225");
        assertEquals(1, result.getInstruments().size());
        assertEquals(0, result.getStructured().size());
        RecordType rec = instrumentBuilder.filteredInstrumentFileToRecordsTypeConverter(result.getInstruments(), list, "2025-07-10").get(0);

        GeneralInformationType gi = rec.getGeneralInformation();
        assertEquals("110109819", rec.getExternalReference());
        assertEquals("Ozon Holdings PLC", gi.getFullName());
        assertEquals("UK", gi.getSearchCode());
        // macro output except the FISN: decision = the real FISN of the structured rows (macro adds the host short name)
        assertEquals(oneLine("ISIN related characteristics : link_entity : 864011; link_csid : 864011; sanctioned_parent_entity : Non-Russian-Holding; confidence_level : 100;\n"
                + "instr_name : 1.875 Ozon 26 CVB-S 1.875 % Convertible Bonds 2021-24.02.26 Reg S; indicative_issue_date : 2021-02-24; instrument_type : Debt;\n"
                + "sanctions_relevant_asset_class : Debt; denomination_currency : USD; maturity_date : 2026-02-24; active_flag : Inactive; issue_date : 2021-02-24;\n"
                + "description : 1.875 Ozon 26 CVB-S 1.875 % Convertible Bonds 2021-24.02.26 Reg S; underlying_ch : 58353386; underlying_isin : US69269L1044;\n"
                + "underlying_gk : 221344 - 864011; underlying_issuer_shortname : Ozon Hldgs; FISN Instrument short name : OZON HOLDINGS P/1.875 FM WC"), gi.getNotes());
        assertEquals(words("sanctions_rationale : The security ISIN XS2304902443 has been issued by the entity GK 864011 [Ozon Holdings PLC] which predominantly derives its revenue from the\n"
                + "Russian Federation. Since the issuance took place after the sanctions effective date, the security is considered sanctioned under the provisions of US regulator(s).\n"
                + "The security is Inactive and thus the status IS FINAL."), words(gi.getComments()));
        assertEquals(1, gi.getPrograms().getProgram().size());
        assertEquals("UK - The Russia (Sanctions) (EU Exit) Regulations 2019", gi.getPrograms().getProgram().get(0).getLabel());
        assertEquals("RUSSIA_AND_UKRAINE/RUSSIA", gi.getPrograms().getProgram().get(0).getDescription());
        assertEquals(Arrays.asList("Instrument Identifier SEDOL=BLKH0B8", "Instrument Identifier CINS=M7622KAA2",
                "Instrument Identifier GERMAN=A3KL8V", "Instrument Identifier XS_INT_NUMBER=230490244",
                "Instrument Identifier BLOOMBERG_ID=BBG00Z9BQRZ2"), documents(rec));
        assertEquals(Collections.singletonList("ISIN=XS2304902443"), codes(rec));
    }

    @Test
    public void caseB_activeValidInstrument_structuredIgnored() {
        FilteredStructuredFile filtered = ozonStructured("221344");
        filtered.addSixTarget(target("RUSSIA_AND_UKRAINE/RUSSIA", "UK", "The Russia (Sanctions) (EU Exit) Regulations 2019", null));
        SixIsinMergeContent content = new SixIsinMergeContent(Collections.singleton("XS2304902443"), Collections.emptyMap(),
                Collections.emptyMap());
        SixIsinRecordMerger.Result result = merger.merge(Collections.emptyList(), Collections.singletonList(filtered), content, "225");
        assertTrue(result.getInstruments().isEmpty());
        assertTrue(result.getStructured().isEmpty());
    }

    @Test
    public void structuredOnly_twoRows_notesInMacroOrder() {
        FilteredStructuredFile filtered = ozonStructured("221344");
        filtered.addSixTarget(target("RUSSIA_AND_UKRAINE/RUSSIA", "UK", "The Russia (Sanctions) (EU Exit) Regulations 2019", null));
        FilteredStructuredFile raw2 = ozonStructured("864011");
        raw2.setCusip("C1");
        filtered.setCusip("C2");
        Map<String, List<FilteredStructuredFile>> rawStructured = new HashMap<>();
        rawStructured.put("XS2304902443", Collections.singletonList(raw2));
        SixIsinMergeContent content = new SixIsinMergeContent(Collections.emptySet(), Collections.emptyMap(), rawStructured);

        SixIsinRecordMerger.Result result = merger.merge(Collections.emptyList(), Collections.singletonList(filtered), content, "225");
        assertEquals(1, result.getStructured().size());
        RecordType rec = structuredBuilder.filteredStructureFileToRecordsTypeConverter(result.getStructured(), list, "2025-07-10").get(0);
        assertEquals("ISIN related characteristics : host_gk : 864011; confidence_level : 100; indicative_issue_date : 2021-02-24;"
                + " instrument_type : Debt; denomination_currency : USD; maturity_date : 2026-02-24; active_flag : Inactive;"
                + " issue_date : 2021-02-24; description : 1.875 Ozon 26 CVB-S 1.875 % Convertible Bonds 2021-24.02.26 Reg S;"
                + " underlying_ch : 58353386; underlying_isin : US69269L1044; underlying_gk : 221344 - 864011;"
                + " underlying_issuer_shortname : Ozon Hldgs; FISN Instrument short name : OZON HOLDINGS P/1.875 FM WC",
                rec.getGeneralInformation().getNotes());
        assertEquals("Ozon Hldgs", rec.getGeneralInformation().getFullName());
        assertEquals(Arrays.asList("ISIN=XS2304902443", "CUSIP=C2", "CUSIP=C1"), codes(rec));
    }

    @Test
    public void programs_sortedByFullLabel_caseInsensitive_unique() {
        FilteredInstrumentFile f = ozonInstrument();
        f.getSixTargets().clear();
        f.addSixTarget(target("T1", "EU", "b basis", null));
        f.addSixTarget(target("T1", "EU", "A basis", null));
        f.addSixTarget(target("T1", "EU", "A basis", null));
        f.addSixTarget(target("T2", "EU", "A basis", null));
        f.addSixTarget(target("T1", "US_SDN", "x", null));
        RecordType rec = instrumentBuilder.filteredInstrumentFileToRecordsTypeConverter(Collections.singletonList(f), list, "d").get(0);
        List<String> programs = rec.getGeneralInformation().getPrograms().getProgram().stream()
                .map(p -> p.getLabel() + "/" + p.getDescription()).collect(Collectors.toList());
        assertEquals(Arrays.asList("EU - A basis/T1", "EU - A basis/T2", "EU - b basis/T1", "US_SDN - x/T1"), programs);
    }

    @Test
    public void portugalLabelInCapitals() {
        FilteredInstrumentFile f = ozonInstrument();
        f.setPortugal("PT1");
        RecordType rec = instrumentBuilder.filteredInstrumentFileToRecordsTypeConverter(Collections.singletonList(f), list, "d").get(0);
        assertTrue(documents(rec).contains("Instrument Identifier PORTUGAL=PT1"));
    }

    @Test
    public void singleRow_onlyDatesFormatted_nullsUnchanged() {
        FilteredInstrumentFile f = ozonInstrument();
        f.setCapitalChangeDate("bad-date");
        f.setLinkCsid(null);
        FilteredStructuredFile other = ozonStructured("1");
        other.setHostIsin("OTHER");
        other.setHostCh("OTHER-CH");
        FilteredInstrumentFile noIsin = ozonInstrument();
        noIsin.setIsin(null);
        SixIsinRecordMerger.Result result = merger.merge(Arrays.asList(f, noIsin, null), Collections.singletonList(other),
                SixIsinMergeContent.none(), "225");
        assertEquals(1, result.getInstruments().size());
        assertEquals("2021-02-24", f.getIndicativeIssueDate());
        assertEquals("bad-date", f.getCapitalChangeDate());
        assertEquals(null, f.getLinkCsid());
        assertEquals(null, f.getMergedTextTargets());
        assertEquals(1, result.getStructured().size());
        assertEquals("2026-02-24", result.getStructured().get(0).getMaturityDate());
        assertEquals(null, result.getStructured().get(0).getMergedTextTargets());
    }

    @Test
    public void instrumentAndStructuredBothFiltered_oneInstrumentRecord_programsOfBoth() {
        FilteredInstrumentFile f = ozonInstrument();
        f.getSixTargets().clear();
        f.addSixTarget(target("T", "UK", "uk basis", "rationale I"));
        FilteredStructuredFile s = ozonStructured("221344");
        s.addSixTarget(target("T2", "UK", "other basis", null));
        SixIsinRecordMerger.Result result = merger.merge(Collections.singletonList(f), Collections.singletonList(s),
                SixIsinMergeContent.none(), "225");
        assertEquals(1, result.getInstruments().size());
        assertTrue(result.getStructured().isEmpty());
        FilteredInstrumentFile rec = result.getInstruments().get(0);
        assertEquals(2, rec.getSixTargets().size());
        assertEquals("864011", rec.getLinkCsid());
        assertEquals("221344", rec.getUnderlyingGk());
        assertEquals(2, rec.getMergedTextTargets().size());
    }

    @Test
    public void sameExternalReferenceForTwoIsins_fails() {
        FilteredInstrumentFile a = ozonInstrument();
        FilteredInstrumentFile b = ozonInstrument();
        b.setIsin("OTHER");
        List<FilteredInstrumentFile> instruments = Arrays.asList(a, b);
        List<FilteredStructuredFile> noStructured = Collections.emptyList();
        SixIsinMergeContent none = SixIsinMergeContent.none();
        try {
            merger.merge(instruments, noStructured, none, "225");
            fail();
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("Duplicate external reference found"));
        }
    }

    @Test
    public void outputIsins_bothFiles() {
        FilteredStructuredFile s = ozonStructured("1");
        s.setHostIsin("S1");
        FilteredInstrumentFile noIsin = ozonInstrument();
        noIsin.setIsin(null);
        assertEquals(Arrays.asList("XS2304902443", "S1"), new java.util.ArrayList<>(SixIsinRecordMerger.outputIsins(
                Arrays.asList(ozonInstrument(), noIsin, null), Arrays.asList(s, null))));
    }

    @Test
    public void nullContentMaps_treatedAsEmpty() {
        SixIsinMergeContent content = new SixIsinMergeContent(null, null, null);
        assertTrue(content.instrumentRows("A").isEmpty());
        assertEquals(0, content.activeInstrumentCount());
    }

    // ------------------------------------------------------------------------------------------ helpers

    private static String oneLine(String macroDisplay) {
        return macroDisplay.replace("\n", " ");
    }

    private static String words(String text) {
        return text.trim().replaceAll("\\s+", " ");
    }

    private static List<String> documents(RecordType rec) {
        return rec.getExtraEntityRecordDetail().getIdentityDocuments().getDocument().stream()
                .map(d -> d.getAdditionalInfo() + "=" + d.getNumber()).collect(Collectors.toList());
    }

    private static List<String> codes(RecordType rec) {
        return rec.getExtraEntityRecordDetail().getCodes().getCode().stream()
                .map(c -> c.getType() + "=" + c.getValue()).collect(Collectors.toList());
    }
}
