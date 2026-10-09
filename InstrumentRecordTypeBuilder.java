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
public class InstrumentRecordTypeBuilder {

    @Autowired
    private ImportedFileRepository importedFileRepository;

    @Autowired
    private RecordRepository recordRepository;

    public static final String OTHERS = "OTHERS";

    public List<RecordType> filteredInstrumentFileToRecordsTypeConverter(List<FilteredInstrumentFile> childInstru, ReglissList list, String applicationDateFromFileName) {
        return childInstru.stream().map(a -> instrumentToRecordType(a, list, applicationDateFromFileName)).collect(Collectors.toList());
    }

    private String getRecordApplicationDate(FilteredInstrumentFile file, ReglissList childList, String applicationDateFromFileName) {
        return childList.getLastVersion()
                .flatMap(v -> recordRepository
                        .findByExternalReferenceAndVersionId(file.getChValor(), v.getId())
                        .map(Record::getStartDateStr))
                .orElse(applicationDateFromFileName);
    }

    private RecordType instrumentToRecordType(FilteredInstrumentFile file, ReglissList childList, String applicationDateFromFileName) {
        GeneralInformationType generalInformationType = generatInfoTypeBuilder(file);
        ExtraEntityRecordDetailType extraEntityRecordDetail = getExtraEntityRecordDetailType(file);
        ExtraOtherRecordDetailType extraOtherRecordDetail = ExtraOtherRecordDetailType.builder().aliases(AliasesType.builder().build()).category("").build();

        return RecordType.builder().generalInformation(generalInformationType)
                .extraEntityRecordDetail(extraEntityRecordDetail).extraOtherRecordDetail(extraOtherRecordDetail)
                .applicationDate(getRecordApplicationDate(file, childList, applicationDateFromFileName)).deletionDate(null).externalReference(file.getChValor()).build();
    }

    private GeneralInformationType generatInfoTypeBuilder(FilteredInstrumentFile file) {
        return GeneralInformationType.builder().fullName(file.getNameDirectIssuer()).type("Entity")
                .searchCode(getSearchCode(file)).description("")
                .instruction("").comments(getComments(file)).notes(getNotes(file))
                .programs(getProgramValues(file)).sanctions(SanctionsType.builder().build()).sources(SourcesType.builder().build()).build();
    }

    private ProgramsType getProgramValues(FilteredInstrumentFile file) {
        List<ProgramType> programTypeList = file.getSixTargets().stream().filter(t -> t.getLegalBasis() != null && t.getRegime() != null)
                .sorted(Comparator.comparing(t -> t.getRegime())).map(this :: programTypeMapping).collect(Collectors.toList());
        return ProgramsType.builder().program(programTypeList.stream().distinct().collect(Collectors.toList())).build();
    }

    private ProgramType programTypeMapping(FilteredSixTarget filteredSixTarget) {
        return ProgramType.builder().label(filteredSixTarget.getRegime()+ " - "+ filteredSixTarget.getLegalBasis()).type("BNPP Input").description(filteredSixTarget.getTarget()).build();
    }

    private String getNotes(FilteredInstrumentFile file) {
        StringBuilder a =  new StringBuilder("ISIN related characteristics : ");
        buildNotes(file, a);
        buildNotes1(file, a);
        buildNotes2(file, a);
        return a.substring(0, a.length() - 2);
    }

    private StringBuilder buildNotes2(FilteredInstrumentFile file, StringBuilder a) {
        if (StringUtils.isNotBlank(file.getMaturityDate())) {
            a.append("maturity_date : "+ file.getMaturityDate() + "; ");
        }
        if (StringUtils.isNotBlank(file.getDebtLifetimeInDays())) {
            a.append("debt_lifetime_in_days : "+ file.getDebtLifetimeInDays() + "; ");
        }
        if (StringUtils.isNotBlank(file.getActiveFlag())) {
            a.append("active_flag : "+ file.getActiveFlag() + "; ");
        }
        if (StringUtils.isNotBlank(file.getIssueDate())) {
            a.append("issue_date : "+ file.getIssueDate() + "; ");
        }
        if (StringUtils.isNotBlank(file.getCapitalChangeDate())) {
            a.append("capital_change_date : "+ file.getCapitalChangeDate() + "; ");
        }
        String reasonForChange = getValueReasonForChange(file);
        if (StringUtils.isNotBlank(reasonForChange)) {
            a.append("reason_for_change : "+ reasonForChange + "; ");
        }
        return a;
    }

    private StringBuilder buildNotes1(FilteredInstrumentFile file, StringBuilder a) {
        if (StringUtils.isNotBlank(file.getIndicativeIssueDate())) {
            a.append("indicative_issue_date : "+ file.getIndicativeIssueDate() + "; ");
        }
        if (StringUtils.isNotBlank(file.getInstrumentType())) {
            a.append("instrument_type : "+ file.getInstrumentType() + "; ");
        }
        if (StringUtils.isNotBlank(file.getSanctionsRelevantAssetClass())) {
            a.append("sanctions_relevant_asset_class : "+ file.getSanctionsRelevantAssetClass() + "; ");
        }
        if (StringUtils.isNotBlank(file.getMainInstrument())) {
            a.append("main_instrument : "+ file.getMainInstrument() + "; ");
        }
        if (StringUtils.isNotBlank(file.getEquityTypeOfIssuance())) {
            a.append("equity_type_of_issuance : "+ file.getEquityTypeOfIssuance() + "; ");
        }
        if (StringUtils.isNotBlank(file.getDenominationCurrency())) {
            a.append("denomination_currency : "+ file.getDenominationCurrency() + "; ");
        }
        return a;
    }

    private String getValueReasonForChange(FilteredInstrumentFile file) {
        return file.getSixTargets().stream().filter(r -> (r.getReasonForChange() != null && !r.getReasonForChange().isEmpty())).map(a -> a.getReasonForChange()).distinct().collect(Collectors.joining(" - "));   // TODO: delimiter partly off-screen - check in the IDE
    }

    private StringBuilder buildNotes(FilteredInstrumentFile file, StringBuilder a) {
        if (StringUtils.isNotBlank(file.getLinkEntity())) {
            a.append("link_entity : "+ file.getLinkEntity() + "; ");
        }
        if (StringUtils.isNotBlank(file.getLinkCsid())) {
            a.append("link_csid : "+ file.getLinkCsid() + "; ");
        }
        if (StringUtils.isNotBlank(file.getSanctionedParentEntity())) {
            a.append("sanctioned_parent_entity : "+ file.getSanctionedParentEntity() + "; ");
        }
        if (StringUtils.isNotBlank(file.getConfidenceLevel())) {
            a.append("confidence_level : "+ file.getConfidenceLevel() + "; ");
        }
        if (StringUtils.isNotBlank(file.getInstrName())) {
            a.append("instr_name : "+ file.getInstrName() + "; ");
        }
        if (StringUtils.isNotBlank(file.getFisn())) {
            a.append("FISN Instrument short name : "+ file.getFisn() + "; ");
        }
        return a;
    }

    private String getComments(FilteredInstrumentFile file) {
        return "sanctions_rationale : "+ file.getSixTargets().stream().filter(a -> (a.getSanctionsRationale() != null && !a.getSanctionsRationale().isEmpty())).map(a -> a.getSanctionsRationale())
                .distinct().collect(Collectors.joining(" | "));


    }

    private String getSearchCode(FilteredInstrumentFile file) {
        return file.getSixTargets().stream().filter(target -> target.getLegalBasis() != null).map(FilteredSixTarget::getRegime).distinct().sorted().collect(Collectors.joining(" "));
    }

    private ExtraEntityRecordDetailType getExtraEntityRecordDetailType(FilteredInstrumentFile file) {
        return ExtraEntityRecordDetailType.builder().addresses(AddressesType.builder().build()).identityDocuments(getIdentityDocuments(file))
                .countryDetails(CountryDetailsType.builder().build()).datesDetails(DatesDetailsType.builder().build()).aliases(AliasesType.builder().build())
                .codes(getCodeType(file)).relations(RelationsType.builder().build()).build();
    }

    private IdentityDocumentsType getIdentityDocuments(FilteredInstrumentFile file) {
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

    private List<DocumentType> buildIdentityDocument6(FilteredInstrumentFile file, List<DocumentType> documentTypes) {
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

    private List<DocumentType> buildIdentityDocument5(FilteredInstrumentFile file, List<DocumentType> documentTypes) {
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

    private List<DocumentType> buildIdentityDocument4(FilteredInstrumentFile file, List<DocumentType> documentTypes) {
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

    private List<DocumentType> buildIdentityDocument3(FilteredInstrumentFile file, List<DocumentType> documentTypes) {
        if (StringUtils.isNotBlank(file.getItalian())) {
            List<String> values = file.getItalian().contains(" - ") ? Arrays.stream(file.getItalian().split(" - ")).map(String::trim).collect(Collectors.toList()) : Arrays.asList(file.getItalian());
            for (String value : values) {
                documentTypes.add(DocumentType.builder().type(OTHERS).issueDate("").expirationDate("").country("").additionalInfo("Instrument Identifier ITALIAN").number(value).build());
            }
        }
        if (StringUtils.isNotBlank(file.getJapaneseCurrent())) {
            List<String> values = file.getJapaneseCurrent().contains(" - ") ? Arrays.stream(file.getJapaneseCurrent().split(" - ")).map(String::trim).collect(Collectors.toList())
                    : Arrays.asList(file.getJapaneseCurrent());
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

    private List<DocumentType> buildIdentityDocument2(FilteredInstrumentFile file, List<DocumentType> documentTypes) {
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
        if (StringUtils.isNotBlank(file.getFranceEuroclear())) {
            List<String> values = file.getFranceEuroclear().contains(" - ") ? Arrays.stream(file.getFranceEuroclear().split(" - ")).map(String::trim).collect(Collectors.toList())
                    : Arrays.asList(file.getFranceEuroclear());
            for (String value : values) {
                documentTypes.add(DocumentType.builder().type(OTHERS).issueDate("").expirationDate("").country("").additionalInfo("Instrument Identifier FRANCE_EUROCLEAR").number(value).build());
            }
        }
        return documentTypes;
    }

    private List<DocumentType> buildIdentityDocument1(FilteredInstrumentFile file, List<DocumentType> documentTypes) {
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

    private List<DocumentType> buildIdentityDocument(FilteredInstrumentFile file, List<DocumentType> documentTypes) {
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

    private CodesType getCodeType(FilteredInstrumentFile file) {
        List<CodeType> codeTypes = new ArrayList<>();
        if (StringUtils.isNotBlank(file.getIsin())) {
            codeTypes.add(CodeType.builder().value(file.getIsin()).type("ISIN").build());
        }
        if (StringUtils.isNotBlank(file.getCusip())) {
            List<String> values = file.getCusip().contains(" - ") ? Arrays.stream(file.getCusip().split(" - ")).map(String::trim).collect(Collectors.toList()) : Arrays.asList(file.getCusip());
            for (String value : values) {
                codeTypes.add(CodeType.builder().value(value).type("CUSIP").build());
            }
        }
        return CodesType.builder().code(codeTypes).build();
    }
}
