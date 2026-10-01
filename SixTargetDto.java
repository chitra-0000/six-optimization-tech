package com.bnpp.regliss.facade.dto.six;


import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

public class SixTargetDto {

    @Getter @Setter
    @JsonProperty("TARGET")
    private String target;

    @Getter @Setter
    @JsonProperty("REGIME")
    private String regime;

    @Getter @Setter
    @JsonProperty("LEGAL_BASIS")
    private String legalBasis;

    @Getter @Setter
    @JsonProperty("SANCTIONED")
    private String sanctioned;

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
}
