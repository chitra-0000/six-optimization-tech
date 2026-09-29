package com.bnpp.regliss.six.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;                          // use jakarta.persistence.* if the project is on Spring Boot 3
import javax.xml.bind.annotation.XmlTransient;       // jakarta.xml.bind.annotation.XmlTransient on Spring Boot 3
import java.util.Objects;

// TODO: re-add remaining project imports (Alt+Enter / Optimize Imports) for: AbstractSimpleEntity

@Entity
@Table(name = "FILTERED_SIX_TARGET")
public class FilteredSixTarget extends AbstractSimpleEntity {

    @Id
    @Getter
    @Setter
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    protected Long id;

    @Getter @Setter
    @JoinColumn(name = "SIX_INSTRU_ID")
    @ManyToOne
    @XmlTransient
    private FilteredInstrumentFile instrumentFile;

    @Getter @Setter
    @JoinColumn(name = "SIX_STRUCT_ID")
    @ManyToOne
    @XmlTransient
    private FilteredStructuredFile structuredFile;

    @Getter @Setter
    @JoinColumn(name = "SIX_OPT_ID")
    @ManyToOne
    @XmlTransient
    private FilteredOptionsFile optionsFile;

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
    @Column(name = "REASON_FOR_CHANGE", length = 2000)
    private String reasonForChange;

    @Getter @Setter
    @Column(name = "SANCTIONS_RATIONALE", length = 4000)
    private String sanctionsRationale;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FilteredSixTarget)) return false;
        FilteredSixTarget that = (FilteredSixTarget) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }

    public void setInstrumentsFile(FilteredInstrumentFile instrumentFile) {
        this.instrumentFile = instrumentFile;
        if (instrumentFile != null && !instrumentFile.getSixTargets().contains(this)) {
            instrumentFile.getSixTargets().add(this);
        }
    }

    public void setStructuredsFile(FilteredStructuredFile structuredFile) {
        this.structuredFile = structuredFile;
        if (structuredFile != null && !structuredFile.getSixTargets().contains(this)) {
            structuredFile.getSixTargets().add(this);
        }
    }
    public void setOptionFile(FilteredOptionsFile optionsFile) {
        this.optionsFile = optionsFile;
        if (optionsFile != null && !optionsFile.getSixTargets().contains(this)) {
            optionsFile.getSixTargets().add(this);
        }
    }

}
