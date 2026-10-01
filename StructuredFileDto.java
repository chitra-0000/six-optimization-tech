package com.bnpp.regliss.facade.dto.six;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

public class StructuredFileDto {

    public StructuredFileDto() {
        // no arg constructor
    }

    @Getter @Setter
    @JsonProperty("LIST_ID")
    public ReglissList list;

    @Getter @Setter
    @JsonProperty("VERSION_ID")
    public Version version;

    @Getter @Setter
    @JsonProperty("HOST_CH")
    public String hostCh;


    @Getter @Setter
    @JsonProperty("HOST_ISIN")
    public String hostIsin;

    @Getter @Setter
    @JsonProperty("HOST_GK")
    public String hostGk;

    @Getter @Setter
    @JsonProperty("HOST_ISSUER_SHORTNAME")
    public String hostIssuerShortname;

    @Getter @Setter
    @JsonProperty("DESCRIPTION")
    public String description;

    @Getter @Setter
    @JsonProperty("FISN")
    public String fisn;

    @Getter @Setter
    @JsonProperty("DATE_OPENED_IN_SIX")
    public String dateOpenedInSix;

    @Getter @Setter
    @JsonProperty("SUBSTITUTE_ISSUE_DATE")
    public String substituteIssueDate;

    @Getter @Setter
    @JsonProperty("INDICATIVE_ISSUE_DATE")
    public String indicativeIssueDate;

    @Getter @Setter
    @JsonProperty("ISSUE_DATE")
    public String issueDate;

    @Getter @Setter
    @JsonProperty("SETTLEMENT_STYLE")
    public String settlementStyle;

    @Getter @Setter
    @JsonProperty("INSTRUMENT_TYPE")
    public String instrumentType;

    @Getter @Setter
    @JsonProperty("DENOMINATION_CURRENCY")
    public String denominationCurrency;

    @Getter @Setter
    @JsonProperty("MATURITY_DATE")
    public String maturityDate;

    @Getter @Setter
    @JsonProperty("ACTIVE_FLAG")
    public String activeFlag;

    @Getter @Setter
    @JsonProperty("UNDERLYING_CH")
    public String underlyingCh;

    @Getter @Setter
    @JsonProperty("UNDERLYING_ISIN")
    public String underlyingIsin;

    @Getter @Setter
    @JsonProperty("UNDERLYING_GK")
    public String underlyingGk;

    @Getter @Setter
    @JsonProperty("UNDERLYING_ISSUER_SHORTNAME")
    public String underlyingIssuerShortname;

    @Getter @Setter
    @JsonProperty("SANCTIONED")
    public String sanctioned;

    @Getter @Setter
    @JsonProperty("SEDOL")
    public String sedol;

    @Getter @Setter
    @JsonProperty("CUSIP")
    public String cusip;

    @Getter @Setter
    @JsonProperty("CINS")
    public String cins;

    @Getter @Setter
    @JsonProperty("FIGI_GLOBAL_ID")
    public String figiGlobalId;

    @Getter @Setter
    @JsonProperty("FIGI_GLOBAL_SHARE_CLASS_ID")
    public String figiGlobalShareClassLevelId;

    @Getter @Setter
    @JsonProperty("AUSTRIAN")
    public String austrian;

    @Getter @Setter
    @JsonProperty("BELGIAN")
    public String belgian;

    @Getter @Setter
    @JsonProperty("CANADIAN")
    public String canadian;

    @Getter @Setter
    @JsonProperty("GERMAN")
    public String german;

    @Getter @Setter
    @JsonProperty("DENMARK")
    public String denmark;

    @Getter @Setter
    @JsonProperty("FRANCE_RGA")
    public String franceRga;

    @Getter @Setter
    @JsonProperty("FRANCE_EUROCLEAR")
    public String franceEuroClear;

    @Getter @Setter
    @JsonProperty("ITALIAN")
    public String italian;

    @Getter @Setter
    @JsonProperty("JAPANESE_CURRENT")
    public String japaneseCurrent;

    @Getter @Setter
    @JsonProperty("JAPANESE_NEW")
    public String japaneseNew;

    @Getter @Setter
    @JsonProperty("LUXEMBOURG")
    public String luxembourg;

    @Getter @Setter
    @JsonProperty("NETHERLAND")
    public String netherland;

    @Getter @Setter
    @JsonProperty("NORWEGIAN")
    public String norwegian;

    @Getter @Setter
    @JsonProperty("SWEDISH")
    public String swedish;

    @Getter @Setter
    @JsonProperty("XS_INT_NUMBER")
    public String xsIntNumber;

    @Getter @Setter
    @JsonProperty("PORTUGAL")
    public String portugal;

    @Getter @Setter
    @JsonProperty("SOUTH_KOREA")
    public String southKorea;

    @Getter @Setter
    @JsonProperty("HONG_KONG")
    public String hongKong;

    @Getter @Setter
    @JsonProperty("TARGET")
    public String target;

    @Getter @Setter
    @JsonProperty("STATUS")
    public String status;

    @Getter @Setter
    @JsonProperty("CONFIDENCE_LEVEL")
    public String confidenceLevel;

    @Getter @Setter
    @JsonProperty("REASON_FOR_CHANGE")
    public String reasonForChange;

    @Getter @Setter
    @JsonProperty("REGIMES")
    public String regimes;

    @Getter @Setter
    @JsonProperty("TARGETS")
    private List<SixTargetDto> targets;
}
