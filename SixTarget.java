package com.bnpp.regliss.six.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;                          // use jakarta.persistence.* if the project is on Spring Boot 3
import javax.xml.bind.annotation.XmlTransient;       // jakarta.xml.bind.annotation.XmlTransient on Spring Boot 3
import java.util.Objects;

// TODO: re-add remaining project imports (Alt+Enter / Optimize Imports) for: AbstractSimpleEntity

@Entity
@Table(name = "SIX_TARGET")
public class SixTarget extends AbstractSimpleEntity {

    @Id
    @Getter @Setter
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    protected Long id;

    @Getter @Setter
    @ManyToOne
    @JoinColumn(name = "SIX_INSTRU_ID")
    @XmlTransient
    private InstrumentFile sixInstruId;

    @Getter @Setter
    @ManyToOne
    @JoinColumn(name = "SIX_STRUCT_ID")
    @XmlTransient
    private StructuredFile sixStructId;

    @Getter @Setter
    @ManyToOne
    @JoinColumn(name = "SIX_OPT_ID")
    @XmlTransient
    private OptionsFile sixOptId;

    @Getter @Setter
    @Column(name = "TARGET")
    private String target;

    @Getter @Setter
    @Column(name = "REGIME")
    private String regime;

    @Getter @Setter
    @Column(name = "LEGAL_BASIS")
    private String legalBasis;

    @Getter @Setter
    @Column(name = "SANCTIONED")
    private String sanctioned;

    @Getter @Setter
    @Column(name = "STATUS")
    private String status;

    @Getter @Setter
    @Column(name = "SANCTION_FLAG_CHANGED")
    private String sanctionFlagChanged;

    @Getter @Setter
    @Column(name = "REASON_FOR_CHANGE", length = 2000)
    private String reasonForChange;

    @Getter @Setter
    @Column(name = "SANCTIONS_RATIONALE", length = 4000)
    private String sanctionsRationale;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SixTarget)) return false;
        SixTarget that = (SixTarget) o;
        return Objects.equals(target, that.target) &&
                Objects.equals(regime, that.regime) &&
                Objects.equals(legalBasis, that.legalBasis);
    }

    @Override
    public int hashCode() { return Objects.hash(target, regime, legalBasis); }

    public void setInstrumentsFile(InstrumentFile instrumentFile) {
        this.sixInstruId = instrumentFile;
        if (instrumentFile != null && !instrumentFile.getSixTargets().contains(this)) {
            instrumentFile.getSixTargets().add(this);
        }
    }

    public void setStructuredsFile(StructuredFile structuredFile) {
        this.sixStructId = structuredFile;
        if (structuredFile != null && !structuredFile.getSixTargets().contains(this)) {
            structuredFile.getSixTargets().add(this);
        }
    }
    public void setOptionFile(OptionsFile optionsFile) {
        this.sixOptId = optionsFile;
        if (optionsFile != null && !optionsFile.getSixTargets().contains(this)) {
            optionsFile.getSixTargets().add(this);
        }
    }
}
