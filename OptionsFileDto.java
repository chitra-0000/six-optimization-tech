package com.bnpp.regliss.facade.dto.six;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

public class OptionsFileDto {
    public OptionsFileDto() {
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
    @JsonProperty("CH_OPTION")
    private String chOption;

    @Getter @Setter
    @JsonProperty("ISIN_OPTION")
    private String isinOption;

    @Getter @Setter
    @JsonProperty("DESCRIPTION")
    private String description;

    @Getter @Setter
    @JsonProperty("FISN")
    private String fisn;

    @Getter @Setter
    @JsonProperty("ISSUER_GK")
    private String issuerGk;

    @Getter @Setter
    @JsonProperty("ISSUER_NAME")
    private String issuerName;

    @Getter @Setter
    @JsonProperty("DATE_OPENED_IN_SIX")
    private String dateOpenedInSix;

    @Getter @Setter
    @JsonProperty("EXPIRY_DATE")
    private String expiryDate;

    @Getter @Setter
    @JsonProperty("INSTRUMENT_TYPE")
    private String instrumentType;

    @Getter @Setter
    @JsonProperty("DENOMINATION_CURRENCY")
    private String denominationCurrency;

    @Getter @Setter
    @JsonProperty("ACTIVE_FLAG")
    private String activeFlag;

    @Getter @Setter
    @JsonProperty("ISSUE_DATE")
    private String issueDate;

    @Getter @Setter
    @JsonProperty("UNDERLYING_CH")
    private String underlyingCh;

    @Getter @Setter
    @JsonProperty("UNDERLYING_ISIN")
    private String underlyingIsin;

    @Getter @Setter
    @JsonProperty("UNDERLYING_ISSUER_GK")
    private String underlyingIssuerGk;

    @Getter @Setter
    @JsonProperty("UNDERLYING_ISSUER_NAME")
    private String underlyingIssuerName;

    @Getter @Setter
    @JsonProperty("SANCTIONED")
    private String sanctioned;

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
    @JsonProperty("TARGET")
    private String target;

    @Getter @Setter
    @JsonProperty("STATUS")
    private String status;

    @Getter @Setter
    @JsonProperty("CONFIDENCE_LEVEL")
    private String confidenceLevel;

    @Getter @Setter
    @JsonProperty("REASON_FOR_CHANGE")
    private String reasonForChange;

    @Getter @Setter
    @JsonProperty("REGIMES")
    public String regimes;

    @Getter @Setter
    @JsonProperty("TARGETS")
    private List<SixTargetDto> targets;
}
