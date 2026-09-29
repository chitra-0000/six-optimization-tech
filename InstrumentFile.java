package com.bnpp.regliss.six.entity;

import com.bnpp.regliss.entity.ReglissList;
import lombok.Getter;                // IDE showed "Cannot resolve symbol 'Setter'" - Lombok import was missing
import lombok.Setter;

import javax.persistence.*;          // use jakarta.persistence.* if the project is on Spring Boot 3
import javax.xml.bind.annotation.XmlElement;         // jakarta.xml.bind.annotation.* on Spring Boot 3
import javax.xml.bind.annotation.XmlElementWrapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// TODO: re-add remaining project imports (Alt+Enter / Optimize Imports) for:
// AbstractSimpleEntity, PersistableEntity, SixTarget

@Entity
@Table(name = "SIX_INSTRUMENTS")
public class InstrumentFile extends AbstractSimpleEntity implements PersistableEntity {

    @Id
    @Getter @Setter
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    protected Long id;

    @Getter @Setter
    @JoinColumn(name = "LIST_ID")
    @ManyToOne
    private ReglissList list;

    @Getter @Setter
    @JoinColumn(name = "VERSION_ID")
    @ManyToOne
    private com.bnpp.regliss.entity.Version version;

    @Getter @Setter
    @Column(name = "RECORD_ORIGIN")
    private String recordOrigin;

    @Getter @Setter
    @Column(name = "OLS_YES")
    private String olsYes;

    @Getter @Setter
    @Column(name = "OLS_NO")
    private String olsNo;

    @Getter @Setter
    @Column(name = "LINK_ENTITY")
    private String linkEntity;

    @Getter @Setter
    @Column(name = "LINK_CSID")
    private String linkCsid;

    @Getter @Setter
    @Column(name = "SANCTIONED_PARENT_ENTITY")
    private String sanctionedParentEntity;

    @Getter @Setter
    @Column(name = "NAME_DIRECT_ISSUER")
    private String nameDirectIssuer;

    @Getter @Setter
    @Column(name = "CONFIDENCE_LEVEL")
    private String confidenceLevel;

    @Getter @Setter
    @Column(name = "ISIN")
    private String isin;

    @Getter @Setter
    @Column(name = "INSTR_NAME")
    private String instrName;

    @Getter @Setter
    @Column(name = "FISN")
    private String fisn;

    @Getter @Setter
    @Column(name = "CH_VALOR")
    private String chValor;

    @Getter @Setter
    @Column(name = "INDICATIVE_ISSUE_DATE")
    private String indicativeIssueDate;

    @Getter @Setter
    @Column(name = "INSTRUMENT_TYPE")
    private String instrumentType;

    @Getter @Setter
    @Column(name = "SANCTION_RELEVANT_ASSET_CLASS")
    private String sanctionsRelevantAssetClass;

    @Getter @Setter
    @Column(name = "MAIN_INSTRUMENT")
    private String mainInstrument;

    @Getter @Setter
    @Column(name = "EQUITY_TYPE_OF_ISSUANCE")
    private String equityTypeOfIssuance;

    @Getter @Setter
    @Column(name = "DENOMINATION_CURRENCY")
    private String denominationCurrency;

    @Getter @Setter
    @Column(name = "MATURITY_DATE")
    private String maturityDate;

    @Getter @Setter
    @Column(name = "DEBT_LIFETIME_IN_DAYS")
    private String debtLifetimeInDays;

    @Getter @Setter
    @Column(name = "ACTIVE_FLAG")
    private String activeFlag;

    @Getter @Setter
    @Column(name = "DATE_OPENED_IN_SIX")
    private String dateOpenedInSix;

    @Getter @Setter
    @Column(name = "SUBSTITUTE_ISSUE_DATE")
    private String substituteIssueDate;

    @Getter @Setter
    @Column(name = "ISSUE_DATE")
    private String issueDate;

    @Getter @Setter
    @Column(name = "CAPITAL_CHANGE_DATE")
    private String capitalChangeDate;

    @Getter @Setter
    @Column(name = "SEDOL")
    private String sedol;

    @Getter @Setter
    @Column(name = "CUSIP")
    private String cusip;

    @Getter @Setter
    @Column(name = "CINS")
    private String cins;

    @Getter @Setter
    @Column(name = "AUSTRIAN")
    private String austrian;

    @Getter @Setter
    @Column(name = "BELGIAN")
    private String belgian;

    @Getter @Setter
    @Column(name = "CANADIAN")
    private String canadian;

    @Getter @Setter
    @Column(name = "GERMAN")
    private String german;

    @Getter @Setter
    @Column(name = "DENMARK")
    private String denmark;

    @Getter @Setter
    @Column(name = "FRANCE_RGA")
    private String franceRga;

    @Getter @Setter
    @Column(name = "FRANCE_EUROCLEAR")
    private String franceEuroclear;

    @Getter @Setter
    @Column(name = "ITALIAN")
    private String italian;

    @Getter @Setter
    @Column(name = "JAPANESE_CURRENT")
    private String japaneseCurrent;

    @Getter @Setter
    @Column(name = "JAPANESE_NEW")
    private String japaneseNew;

    @Getter @Setter
    @Column(name = "LUXEMBOURG")
    private String luxembourg;

    @Getter @Setter
    @Column(name = "NETHERLAND")
    private String netherland;

    @Getter @Setter
    @Column(name = "NORWEGIAN")
    private String norwegian;

    @Getter @Setter
    @Column(name = "SWEDISH")
    private String swedish;

    @Getter @Setter
    @Column(name = "XS_INT_NUMBER")
    private String xsIntNumber;

    @Getter @Setter
    @Column(name = "PORTUGAL")
    private String portugal;

    @Getter @Setter
    @Column(name = "SOUTH_KOREA")
    private String southKorea;

    @Getter @Setter
    @Column(name = "HONG_KONG")
    private String hongKong;

    @Getter @Setter
    @Column(name = "FIGI_GLOBAL_ID")
    private String figiGlobalId;

    @Getter @Setter
    @Column(name = "FIGI_GLOBAL_SHARE_CLASS_ID")
    private String figiGlobalShareClassLevelId;

    @Getter @Setter
    @Column(name = "OBLIGOR_ROLE")
    private String obligorRole;

    @Getter @Setter
    @Column(name = "UNDERLYING_OBLIGOR")
    private String underlyingObligor;

    @Getter @Setter
    @Column(name = "UNDERLYING_OBLIGOR_GK")
    private String underlyingObligorGk;

    @Getter @Setter
    @Column(name = "REFERENCE_CAPITAL")
    private String referenceCapital;

    @Getter @Setter
    @Column(name = "ACTUAL_CAPITAL")
    private String actualCapital;

    @Getter @Setter
    @Column(name = "REGIMES", length = 2000)
    private String regimes;

    @Getter @Setter
    @OneToMany(mappedBy = "sixInstruId", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)   // TODO: line was cut off after "FetchType.EA" - confirm EAGER in the IDE
    @XmlElementWrapper(name = "TARGETS")
    @XmlElement(name = "SIX_TARGET")
    private List<SixTarget> sixTargets = new ArrayList<>();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InstrumentFile)) return false;
        InstrumentFile that = (InstrumentFile) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }

    public void addSixTarget(SixTarget target) {
        sixTargets.add(target);
        target.setSixInstruId(this);
    }

    public void removeSixTarget(SixTarget target) {
        sixTargets.remove(target);
        target.setSixInstruId(null);
    }

    @Override
    public void setVersion(com.bnpp.regliss.entity.Version version) { this.version = version; }

    @Override
    public void setList(ReglissList list) { this.list = list; }

}
