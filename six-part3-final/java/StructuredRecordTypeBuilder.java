package com.bnpp.regliss.importer.six.extractor;

import org.apache.commons.lang3.StringUtils;   // TODO: check in the IDE (imports folded)
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class StructuredRecordTypeBuilder {

    @Autowired
    private ImportedFileRepository importedFileRepository;

    @Autowired
    private SixRecordStartDates sixRecordStartDates;

    public static final String OTHERS = "OTHERS";

    public List<RecordType> filteredStructureFileToRecordsTypeConverter(List<FilteredStructuredFile> childInstru, ReglissList list, String applicationDateFromFileName) {
        // start dates of the list's last version: ONE query for the whole list (before: one query per record)
        SixRecordStartDates.Lookup startDates = sixRecordStartDates.ofLastVersion(list);
        return childInstru.stream().map(a -> structureToRecordType(a, startDates, applicationDateFromFileName)).collect(Collectors.toList());
    }

    /** Start date of the record in the list's last version, else the application date of the file name (unchanged rule). */
    private String getRecordApplicationDate(FilteredStructuredFile file, SixRecordStartDates.Lookup startDates, String applicationDateFromFileName) {
        return startDates.startDateOf(file.getHostCh()).orElseGet(() -> applicationDateFromFileName);
    }

    private RecordType structureToRecordType(FilteredStructuredFile file, SixRecordStartDates.Lookup startDates, String applicationDateFromFileName) {
        GeneralInformationType generalInformationType = generatInfoTypeBuilder(file);
        ExtraEntityRecordDetailType extraEntityRecordDetail = getExtraEntityRecordDetailType(file);
        ExtraOtherRecordDetailType extraOtherRecordDetail = ExtraOtherRecordDetailType.builder().aliases(AliasesType.builder().build()).category("").build();

        return RecordType.builder().generalInformation(generalInformationType)
                .extraEntityRecordDetail(extraEntityRecordDetail).extraOtherRecordDetail(extraOtherRecordDetail)
                .applicationDate(getRecordApplicationDate(file, startDates, applicationDateFromFileName)).deletionDate(null).externalReference(file.getHostCh()).build();
    }

    private GeneralInformationType generatInfoTypeBuilder(FilteredStructuredFile file) {
        return GeneralInformationType.builder().fullName(file.getHostIssuerShortname()).type("Entity")
                .searchCode(getSearchCode(file)).description("")
                .instruction("").comments("").notes(getNotes(file))
                .programs(getProgramValues(file)).sanctions(SanctionsType.builder().build()).sources(SourcesType.builder().build()).build();
    }

    private ProgramsType getProgramValues(FilteredStructuredFile file) {
        List<ProgramType> programTypeList = file.getSixTargets().stream().filter(t -> t.getLegalBasis() != null && t.getRegime() != null)
                .sorted(Comparator.comparing(t -> t.getRegime())).map(this :: programTypeMapping).distinct().collect(Collectors.toList());
        return ProgramsType.builder().program(programTypeList).build();
    }

    private ProgramType programTypeMapping(FilteredSixTarget filteredSixTarget) {
        return ProgramType.builder().label(filteredSixTarget.getRegime()+ " - "+ filteredSixTarget.getLegalBasis()).type("BNPP Input").description(filteredSixTarget.getTarget()).build();
    }

    private String getNotes(FilteredStructuredFile file) {
        StringBuilder a =  new StringBuilder("ISIN related characteristics : ");
        buildNotes(file, a);
        buildNotes1(file, a);
        buildNotes2(file, a);
        return a.substring(0, a.length() - 2);
    }

    private StringBuilder buildNotes2(FilteredStructuredFile file, StringBuilder a) {
        if (StringUtils.isNotBlank(file.getUnderlyingIsin())) {
            a.append("underlying_isin : "+ file.getUnderlyingIsin() + "; ");
        }
        if (StringUtils.isNotBlank(file.getUnderlyingGk())) {
            a.append("underlying_gk : "+ file.getUnderlyingGk() + "; ");
        }
        if (StringUtils.isNotBlank(file.getUnderlyingIssuerShortname())) {
            a.append("underlying_issuer_shortname : "+ file.getUnderlyingIssuerShortname() + "; ");
        }
        String reasonForChange = getValueReasonForChange(file);
        if (StringUtils.isNotBlank(reasonForChange)) {
            a.append("reason_for_change : "+ reasonForChange + "; ");
        }
        return a;
    }

    private StringBuilder buildNotes1(FilteredStructuredFile file, StringBuilder a) {
        if (StringUtils.isNotBlank(file.getDenominationCurrency())) {
            a.append("denomination_currency : "+ file.getDenominationCurrency() + "; ");
        }
        if (StringUtils.isNotBlank(file.getMaturityDate())) {
            a.append("maturity_date : "+ file.getMaturityDate() + "; ");
        }
        if (StringUtils.isNotBlank(file.getActiveFlag())) {
            a.append("active_flag : "+ file.getActiveFlag() + "; ");
        }
        if (StringUtils.isNotBlank(file.getIssueDate())) {
            a.append("issue_date : "+ file.getIssueDate() + "; ");
        }
        if (StringUtils.isNotBlank(file.getUnderlyingCh())) {
            a.append("underlying_ch : "+ file.getUnderlyingCh() + "; ");
        }
        return a;
    }

    private StringBuilder buildNotes(FilteredStructuredFile file, StringBuilder a) {
        if (StringUtils.isNotBlank(file.getHostGk())) {
            a.append("host_gk : "+ file.getHostGk() + "; ");
        }
        if (StringUtils.isNotBlank(file.getConfidenceLevel())) {
            a.append("confidence_level : "+ file.getConfidenceLevel() + "; ");
        }
        if (StringUtils.isNotBlank(file.getDescription())) {
            a.append("description : "+ file.getDescription() + "; ");
        }
        if (StringUtils.isNotBlank(file.getFisn())) {
            a.append("FISN Instrument short name : "+ file.getFisn() + "; ");
        }
        if (StringUtils.isNotBlank(file.getIndicativeIssueDate())) {
            a.append("indicative_issue_date : "+ file.getIndicativeIssueDate() + "; ");
        }
        if (StringUtils.isNotBlank(file.getInstrumentType())) {
            a.append("instrument_type : "+ file.getInstrumentType() + "; ");
        }
        return a;
    }

    private String getValueReasonForChange(FilteredStructuredFile file) {
        return file.getSixTargets().stream().filter(r -> (r.getReasonForChange() != null && !r.getReasonForChange().isEmpty())).map(a -> a.getReasonForChange()).distinct().collect(Collectors.joining(" - "));   // TODO: delimiter partly off-screen - check in the IDE
    }

    private String getSearchCode(FilteredStructuredFile file) {
        return file.getSixTargets().stream().filter(target -> target.getLegalBasis() != null).map(FilteredSixTarget::getRegime).distinct().sorted().collect(Collectors.joining(" "));
    }

    private ExtraEntityRecordDetailType getExtraEntityRecordDetailType(FilteredStructuredFile file) {
        return ExtraEntityRecordDetailType.builder().addresses(AddressesType.builder().build()).identityDocuments(getIdentityDocuments(file))
                .countryDetails(CountryDetailsType.builder().build()).datesDetails(DatesDetailsType.builder().build()).aliases(AliasesType.builder().build())
                .codes(getCodeType(file)).relations(RelationsType.builder().build()).build();
    }

    private IdentityDocumentsType getIdentityDocuments(FilteredStructuredFile file) {
        List<DocumentType> documentTypes = new ArrayList<>();

        buildIdentityDocument(file, documentTypes);
        buildIdentityDocument1(file, documentTypes);
        buildIdentityDocument2(file, documentTypes);
        buildIdentityDocument3(file, documentTypes);
        buildIdentityDocument4(file, documentTypes);
        buildIdentityDocument5(file, documentTypes);
        buildIdentityDocument6(file, documentTypes);
        return IdentityDocumentsType.builder().document(documentTypes.stream().distinct().collect(Collectors.toList())).build();
    }

    private List<DocumentType> buildIdentityDocument6(FilteredStructuredFile file, List<DocumentType> documentTypes) {
        if (StringUtils.isNotBlank(file.getSouthKorea())) {
            List<String> values = file.getSouthKorea().contains(" - ") ? Arrays.stream(file.getSouthKorea().split(" - ")).map(String::trim).collect(Collectors.toList()) : Arrays.asList(file.getSouthKorea());
            for (String value : values) {
                documentTypes.add(DocumentType.builder().type(OTHERS).issueDate("").expirationDate("").country("").additionalInfo("Instrument Identifier SOUTH_KOREA").number(value).build());
            }
        }
        if (StringUtils.isNotBlank(file.getHongKong())) {
            List<String> values = file.getHongKong().contains(" - ") ? Arrays.stream(file.getHongKong().split(" - ")).map(String::trim).collect(Collectors.toList()) : Arrays.asList(file.getHongKong());
            for (String value : values) {
                documentTypes.add(DocumentType.builder().type(OTHERS).issueDate("").expirationDate("").country("").additionalInfo("Instrument Identifier HONG_KONG").number(value).build());
            }
        }
        if (StringUtils.isNotBlank(file.getFigiGlobalId())) {
            List<String> values = file.getFigiGlobalId().contains(" - ") ? Arrays.stream(file.getFigiGlobalId().split(" - ")).map(String::trim).collect(Collectors.toList()) : Arrays.asList(file.getFigiGlobalId());
            for (String value : values) {
                documentTypes.add(DocumentType.builder().type(OTHERS).issueDate("").expirationDate("").country("").additionalInfo("Instrument Identifier BLOOMBERG_ID").number(value).build());
            }
        }
        return documentTypes;
    }

    private List<DocumentType> buildIdentityDocument5(FilteredStructuredFile file, List<DocumentType> documentTypes) {
        if (StringUtils.isNotBlank(file.getSwedish())) {
            List<String> values = file.getSwedish().contains(" - ") ? Arrays.stream(file.getSwedish().split(" - ")).map(String::trim).collect(Collectors.toList()) : Arrays.asList(file.getSwedish());
            for (String value : values) {
                documentTypes.add(DocumentType.builder().type(OTHERS).issueDate("").expirationDate("").country("").additionalInfo("Instrument Identifier SWEDISH").number(value).build());
            }
        }
        if (StringUtils.isNotBlank(file.getXsIntNumber())) {
            List<String> values = file.getXsIntNumber().contains(" - ") ? Arrays.stream(file.getXsIntNumber().split(" - ")).map(String::trim).collect(Collectors.toList()) : Arrays.asList(file.getXsIntNumber());
            for (String value : values) {
                documentTypes.add(DocumentType.builder().type(OTHERS).issueDate("").expirationDate("").country("").additionalInfo("Instrument Identifier XS_INT_NUMBER").number(value).build());
            }
        }
        if (StringUtils.isNotBlank(file.getPortugal())) {
            List<String> values = file.getPortugal().contains(" - ") ? Arrays.stream(file.getPortugal().split(" - ")).map(String::trim).collect(Collectors.toList()) : Arrays.asList(file.getPortugal());
            for (String value : values) {
                documentTypes.add(DocumentType.builder().type(OTHERS).issueDate("").expirationDate("").country("").additionalInfo("Instrument Identifier Portugal").number(value).build());
            }
        }
        return documentTypes;
    }

    private List<DocumentType> buildIdentityDocument4(FilteredStructuredFile file, List<DocumentType> documentTypes) {
        if (StringUtils.isNotBlank(file.getLuxembourg())) {
            List<String> values = file.getLuxembourg().contains(" - ") ? Arrays.stream(file.getLuxembourg().split(" - ")).map(String::trim).collect(Collectors.toList()) : Arrays.asList(file.getLuxembourg());
            for (String value : values) {
                documentTypes.add(DocumentType.builder().type(OTHERS).issueDate("").expirationDate("").country("").additionalInfo("Instrument Identifier LUXEMBOURG").number(value).build());
            }
        }
        if (StringUtils.isNotBlank(file.getNetherland())) {
            List<String> values = file.getNetherland().contains(" - ") ? Arrays.stream(file.getNetherland().split(" - ")).map(String::trim).collect(Collectors.toList()) : Arrays.asList(file.getNetherland());
            for (String value : values) {
                documentTypes.add(DocumentType.builder().type(OTHERS).issueDate("").expirationDate("").country("").additionalInfo("Instrument Identifier NETHERLAND").number(value).build());
            }
        }
        if (StringUtils.isNotBlank(file.getNorwegian())) {
            List<String> values = file.getNorwegian().contains(" - ") ? Arrays.stream(file.getNorwegian().split(" - ")).map(String::trim).collect(Collectors.toList()) : Arrays.asList(file.getNorwegian());
            for (String value : values) {
                documentTypes.add(DocumentType.builder().type(OTHERS).issueDate("").expirationDate("").country("").additionalInfo("Instrument Identifier NORWEGIAN").number(value).build());
            }
        }
        return documentTypes;
    }

    private List<DocumentType> buildIdentityDocument3(FilteredStructuredFile file, List<DocumentType> documentTypes) {
        if (StringUtils.isNotBlank(file.getItalian())) {
            List<String> values = file.getItalian().contains(" - ") ? Arrays.stream(file.getItalian().split(" - ")).map(String::trim).collect(Collectors.toList()) : Arrays.asList(file.getItalian());
            for (String value : values) {
                documentTypes.add(DocumentType.builder().type(OTHERS).issueDate("").expirationDate("").country("").additionalInfo("Instrument Identifier ITALIAN").number(value).build());
            }
        }
        if (StringUtils.isNotBlank(file.getJapaneseCurrent())) {
            List<String> values = file.getJapaneseCurrent().contains(" - ") ? Arrays.stream(file.getJapaneseCurrent().split(" - ")).map(String::trim).collect(Collectors.toList()) : Arrays.asList(file.getJapaneseCurrent());
            for (String value : values) {
                documentTypes.add(DocumentType.builder().type(OTHERS).issueDate("").expirationDate("").country("").additionalInfo("Instrument Identifier JAPANESE_CURRENT").number(value).build());
            }
        }
        if (StringUtils.isNotBlank(file.getJapaneseNew())) {
            List<String> values = file.getJapaneseNew().contains(" - ") ? Arrays.stream(file.getJapaneseNew().split(" - ")).map(String::trim).collect(Collectors.toList()) : Arrays.asList(file.getJapaneseNew());
            for (String value : values) {
                documentTypes.add(DocumentType.builder().type(OTHERS).issueDate("").expirationDate("").country("").additionalInfo("Instrument Identifier JAPANESE_NEW").number(value).build());
            }
        }
        return documentTypes;
    }

    private List<DocumentType> buildIdentityDocument2(FilteredStructuredFile file, List<DocumentType> documentTypes) {
        if (StringUtils.isNotBlank(file.getDenmark())) {
            List<String> values = file.getDenmark().contains(" - ") ? Arrays.stream(file.getDenmark().split(" - ")).map(String::trim).collect(Collectors.toList()) : Arrays.asList(file.getDenmark());
            for (String value : values) {
                documentTypes.add(DocumentType.builder().type(OTHERS).issueDate("").expirationDate("").country("").additionalInfo("Instrument Identifier DENMARK").number(value).build());
            }
        }
        if (StringUtils.isNotBlank(file.getFranceRga())) {
            List<String> values = file.getFranceRga().contains(" - ") ? Arrays.stream(file.getFranceRga().split(" - ")).map(String::trim).collect(Collectors.toList()) : Arrays.asList(file.getFranceRga());
            for (String value : values) {
                documentTypes.add(DocumentType.builder().type(OTHERS).issueDate("").expirationDate("").country("").additionalInfo("Instrument Identifier FRANCE_RGA").number(value).build());
            }
        }
        if (StringUtils.isNotBlank(file.getFranceEuroClear())) {
            List<String> values = file.getFranceEuroClear().contains(" - ") ? Arrays.stream(file.getFranceEuroClear().split(" - ")).map(String::trim).collect(Collectors.toList()) : Arrays.asList(file.getFranceEuroClear());
            for (String value : values) {
                documentTypes.add(DocumentType.builder().type(OTHERS).issueDate("").expirationDate("").country("").additionalInfo("Instrument Identifier FRANCE_EUROCLEAR").number(value).build());
            }
        }
        return documentTypes;
    }

    private List<DocumentType> buildIdentityDocument1(FilteredStructuredFile file, List<DocumentType> documentTypes) {
        if (StringUtils.isNotBlank(file.getBelgian())) {
            List<String> values = file.getBelgian().contains(" - ") ? Arrays.stream(file.getBelgian().split(" - ")).map(String::trim).collect(Collectors.toList()) : Arrays.asList(file.getBelgian());
            for (String value : values) {
                documentTypes.add(DocumentType.builder().type(OTHERS).issueDate("").expirationDate("").country("").additionalInfo("Instrument Identifier BELGIAN").number(value).build());
            }
        }
        if (StringUtils.isNotBlank(file.getCanadian())) {
            List<String> values = file.getCanadian().contains(" - ") ? Arrays.stream(file.getCanadian().split(" - ")).map(String::trim).collect(Collectors.toList()) : Arrays.asList(file.getCanadian());
            for (String value : values) {
                documentTypes.add(DocumentType.builder().type(OTHERS).issueDate("").expirationDate("").country("").additionalInfo("Instrument Identifier CANADIAN").number(value).build());
            }
        }
        if (StringUtils.isNotBlank(file.getGerman())) {
            List<String> values = file.getGerman().contains(" - ") ? Arrays.stream(file.getGerman().split(" - ")).map(String::trim).collect(Collectors.toList()) : Arrays.asList(file.getGerman());
            for (String value : values) {
                documentTypes.add(DocumentType.builder().type(OTHERS).issueDate("").expirationDate("").country("").additionalInfo("Instrument Identifier GERMAN").number(value).build());
            }
        }
        return documentTypes;
    }

    private List<DocumentType> buildIdentityDocument(FilteredStructuredFile file, List<DocumentType> documentTypes) {
        if (StringUtils.isNotBlank(file.getSedol())) {
            List<String> values = file.getSedol().contains(" - ") ? Arrays.stream(file.getSedol().split(" - ")).map(String::trim).collect(Collectors.toList()) : Arrays.asList(file.getSedol());
            for (String value : values) {
                documentTypes.add(DocumentType.builder().type(OTHERS).issueDate("").expirationDate("").country("").additionalInfo("Instrument Identifier SEDOL").number(value).build());
            }
        }

        if (StringUtils.isNotBlank(file.getCins())) {
            List<String> values = file.getCins().contains(" - ") ? Arrays.stream(file.getCins().split(" - ")).map(String::trim).collect(Collectors.toList()) : Arrays.asList(file.getCins());
            for (String value : values) {
                documentTypes.add(DocumentType.builder().type(OTHERS).issueDate("").expirationDate("").country("").additionalInfo("Instrument Identifier CINS").number(value).build());
            }
        }

        if (StringUtils.isNotBlank(file.getAustrian())) {
            List<String> values = file.getAustrian().contains(" - ") ? Arrays.stream(file.getAustrian().split(" - ")).map(String::trim).collect(Collectors.toList()) : Arrays.asList(file.getAustrian());
            for (String value : values) {
                documentTypes.add(DocumentType.builder().type(OTHERS).issueDate("").expirationDate("").country("").additionalInfo("Instrument Identifier AUSTRIAN").number(value).build());
            }
        }
        return documentTypes;
    }

    private CodesType getCodeType(FilteredStructuredFile file) {
        List<CodeType> codeTypes = new ArrayList<>();
        if (StringUtils.isNotBlank(file.getHostIsin())) {
            codeTypes.add(CodeType.builder().value(file.getHostIsin()).type("ISIN").build());
        }
        if (StringUtils.isNotBlank(file.getCusip())) {
            codeTypes.add(CodeType.builder().value(file.getCusip()).type("CUSIP").build());
        }

        return CodesType.builder().code(codeTypes).build();
    }


}
