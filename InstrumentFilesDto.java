package com.bnpp.regliss.facade.dto.six;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

public class InstrumentFilesDto {

    public InstrumentFilesDto() {
        // NO ARGS CONSTRUCTOR
    }

    @Getter @Setter
    @JsonProperty("LIST_ID")
    public ReglissList list;

    @Getter @Setter
    @JsonProperty("VERSION_ID")
    public Version version;

    @Getter
    @Setter
    @JsonProperty("SANCTIONED")
    private String sanctioned;

    @Getter @Setter
    @JsonProperty("RECORD_ORIGIN")
    private String recordOrigin;

    @Getter @Setter
    @JsonProperty("OLS_YES")
    private String olsYes;

    @Getter @Setter
    @JsonProperty("OLS_NO")
    private String olsNo;

    @Getter @Setter
    @JsonProperty("LINK_ENTITY")
    private String linkEntity;

    @Getter @Setter
    @JsonProperty("LINK_CSID")
    private String linkCsid;

    @Getter @Setter
    @JsonProperty("SANCTIONED_PARENT_ENTITY")
    private String sanctionedParentEntity;

    @Getter @Setter
    @JsonProperty("NAME_DIRECT_ISSUER")
    private String nameDirectIssuer;

    @Getter @Setter
    @JsonProperty("CONFIDENCE_LEVEL")
    private String confidenceLevel;

    @Getter @Setter
    @JsonProperty("ISIN")
    private String isin;

    @Getter @Setter
    @JsonProperty("INSTR_NAME")
    private String instrName;

    @Getter @Setter
    @JsonProperty("FISN")
    private String fisn;

    @Getter @Setter
    @JsonProperty("CH_VALOR")
    private String chValor;

    @Getter @Setter
    @JsonProperty("INDICATIVE_ISSUE_DATE")
    private String indicativeIssueDate;

    @Getter @Setter
    @JsonProperty("INSTRUMENT_TYPE")
    private String instrumentType;

    @Getter @Setter
    @JsonProperty("SANCTION_RELEVANT_ASSET_CLASS")
    private String sanctionsRelevantAssetClass;

    @Getter @Setter
    @JsonProperty("MAIN_INSTRUMENT")
    private String mainInstrument;

    @Getter @Setter
    @JsonProperty("EQUITY_TYPE_OF_ISSUANCE")
    private String equityTypeOfIssuance;

    @Getter @Setter
    @JsonProperty("DENOMINATION_CURRENCY")
    private String denominationCurrency;

    @Getter @Setter
    @JsonProperty("MATURITY_DATE")
    private String maturityDate;

    @Getter @Setter
    @JsonProperty("DEBT_LIFETIME_IN_DAYS")
    private String debtLifetimeInDays;

    @Getter @Setter
    @JsonProperty("ACTIVE_FLAG")
    private String activeFlag;

    @Getter @Setter
    @JsonProperty("DATE_OPENED_IN_SIX")
    private String dateOpenedInSix;

    @Getter @Setter
    @JsonProperty("SUBSTITUTE_ISSUE_DATE")
    private String substituteIssueDate;

    @Getter @Setter
    @JsonProperty("ISSUE_DATE")
    private String issueDate;

    @Getter @Setter
    @JsonProperty("CAPITAL_CHANGE_DATE")
    private String capitalChangeDate;

    @Getter @Setter
    @JsonProperty("SEDOL")
    private String sedol;

    @Getter @Setter
    @JsonProperty("CUSIP")
    private String cusip;

    @Getter @Setter
    @JsonProperty("CINS")
    private String cins;

    @Getter @Setter
    @JsonProperty("AUSTRIAN")
    private String austrian;

    @Getter @Setter
    @JsonProperty("BELGIAN")
    private String belgian;

    @Getter @Setter
    @JsonProperty("CANADIAN")
    private String canadian;

    @Getter @Setter
    @JsonProperty("GERMAN")
    private String german;

    @Getter @Setter
    @JsonProperty("DENMARK")
    private String denmark;

    @Getter @Setter
    @JsonProperty("FRANCE_RGA")
    private String franceRga;

    @Getter @Setter
    @JsonProperty("FRANCE_EUROCLEAR")
    private String franceEuroclear;

    @Getter @Setter
    @JsonProperty("ITALIAN")
    private String italian;

    @Getter @Setter
    @JsonProperty("JAPANESE_CURRENT")
    private String japaneseCurrent;

    @Getter @Setter
    @JsonProperty("JAPANESE_NEW")
    private String japaneseNew;

    @Getter @Setter
    @JsonProperty("LUXEMBOURG")
    private String luxembourg;

    @Getter @Setter
    @JsonProperty("NETHERLAND")
    private String netherland;

    @Getter @Setter
    @JsonProperty("NORWEGIAN")
    private String norwegian;

    @Getter @Setter
    @JsonProperty("SWEDISH")
    private String swedish;

    @Getter @Setter
    @JsonProperty("XS_INT_NUMBER")
    private String xsIntNumber;

    @Getter @Setter
    @JsonProperty("PORTUGAL")
    private String portugal;

    @Getter @Setter
    @JsonProperty("SOUTH_KOREA")
    private String southKorea;

    @Getter @Setter
    @JsonProperty("HONG_KONG")
    private String hongKong;

    @Getter @Setter
    @JsonProperty("FIGI_GLOBAL_ID")
    private String figiGlobalId;

    @Getter @Setter
    @JsonProperty("FIGI_GLOBAL_SHARE_CLASS_ID")
    private String figiGlobalShareClassLevelId;

    @Getter @Setter
    @JsonProperty("OBLIGOR_ROLE")
    private String obligorRole;

    @Getter @Setter
    @JsonProperty("UNDERLYING_OBLIGOR")
    private String underlyingObligor;

    @Getter @Setter
    @JsonProperty("UNDERLYING_OBLIGOR_GK")
    private String underlyingObligorGk;

    @Getter @Setter
    @JsonProperty("REFERENCE_CAPITAL")
    private String referenceCapital;

    @Getter @Setter
    @JsonProperty("ACTUAL_CAPITAL")
    private String actualCapital;

    @Getter @Setter
    @JsonProperty("TARGET")
    private String target;

    @Getter @Setter
    @JsonProperty("STATUS")
    private String status;

    @Getter @Setter
    @JsonProperty("SANCTION_FLAG_CHANGED")
    private String sanctionFlagChanged;

    @Getter @Setter
    @JsonProperty("REASON_FOR_CHANGE")
    private String reasonForChange;

    @Getter @Setter
    @JsonProperty("SANCTIONS_RATIONALE")
    private String sanctionsRationale;

    @Getter @Setter
    @JsonProperty("REGIMES")
    private String regimes;

    @Getter @Setter
    @JsonProperty("TARGETS")
    private List<SixTargetDto> sixTargets;
}
