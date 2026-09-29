package com.bnpp.regliss.six.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;          // use jakarta.persistence.* if the project is on Spring Boot 3
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// TODO: re-add remaining project imports (Alt+Enter / Optimize Imports) for:
// AbstractSimpleEntity, FilteredSixTarget

@Entity
@Table(name = "FILTERED_SIX_INSTRUMENTS")
public class FilteredInstrumentFile extends AbstractSimpleEntity {

    @Id
    @Getter @Setter
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    protected Long id;

    @Getter @Setter
    @Column(name = "SIX_LIST_REF")
    private String listRef;

    @Getter @Setter
    @JoinColumn(name = "VERSION_ID")
    @ManyToOne
    private com.bnpp.regliss.entity.Version version;

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
    @OneToMany(mappedBy = "instrumentFile", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)   // TODO: line was cut off after "fetch" - confirm the FetchType in the IDE
    private List<FilteredSixTarget> sixTargets = new ArrayList<>();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FilteredInstrumentFile)) return false;
        FilteredInstrumentFile that = (FilteredInstrumentFile) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }

    public void addSixTarget(FilteredSixTarget target) {
        sixTargets.add(target);
        target.setInstrumentFile(this);
    }

}
