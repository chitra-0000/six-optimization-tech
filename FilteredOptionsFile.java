package com.bnpp.regliss.six.entity;

import com.bnpp.regliss.entity.ReglissList;
import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;          // use jakarta.persistence.* if the project is on Spring Boot 3
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// TODO: re-add remaining project imports (Alt+Enter / Optimize Imports) for:
// AbstractSimpleEntity, FilteredSixTarget

@Entity
@Table(name = "FILTERED_SIX_OPTION")
public class FilteredOptionsFile extends AbstractSimpleEntity{

    @Id
    @Getter
    @Setter
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    protected Long id;

    @Getter @Setter
    @JoinColumn(name = "LIST_ID")
    @ManyToOne
    private ReglissList list;

    @Getter @Setter
    @Column(name = "SIX_LIST_REF")
    private String listRef;

    @Getter @Setter
    @JoinColumn(name = "VERSION_ID")
    @ManyToOne
    private com.bnpp.regliss.entity.Version version;

    @Getter @Setter
    @Column(name = "CH_OPTION")
    private String chOption;

    @Getter @Setter
    @Column(name = "ISIN_OPTION")
    private String isinOption;

    @Getter @Setter
    @Column(name = "DESCRIPTION", length = 2000)
    private String description;

    @Getter @Setter
    @Column(name = "FISN")
    private String fisn;

    @Getter @Setter
    @Column(name = "ISSUER_GK")
    private String issuerGk;

    @Getter @Setter
    @Column(name = "ISSUER_NAME")
    private String issuerName;

    @Getter @Setter
    @Column(name = "DATE_OPENED_IN_SIX")
    private String dateOpenedInSix;

    @Getter @Setter
    @Column(name = "EXPIRY_DATE")
    private String expiryDate;

    @Getter @Setter
    @Column(name = "INSTRUMENT_TYPE")
    private String instrumentType;

    @Getter @Setter
    @Column(name = "DENOMINATION_CURRENCY")
    private String denominationCurrency;

    @Getter @Setter
    @Column(name = "ACTIVE_FLAG")
    private String activeFlag;

    @Getter @Setter
    @Column(name = "ISSUE_DATE")
    private String issueDate;

    @Getter @Setter
    @Column(name = "UNDERLYING_CH")
    private String underlyingCh;

    @Getter @Setter
    @Column(name = "UNDERLYING_ISIN")
    private String underlyingIsin;

    @Getter @Setter
    @Column(name = "UNDERLYING_ISSUER_GK")
    private String underlyingIssuerGk;

    @Getter @Setter
    @Column(name = "UNDERLYING_ISSUER_NAME")
    private String underlyingIssuerName;

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
    @Column(name = "REGIMES", length = 2000)
    private String regimes;

    @Getter @Setter
    @Column(name = "CONFIDENCE_LEVEL")
    private String confidenceLevel;

    @Getter @Setter
    @OneToMany(mappedBy = "optionsFile", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<FilteredSixTarget> sixTargets = new ArrayList<>();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FilteredOptionsFile)) return false;
        FilteredOptionsFile that = (FilteredOptionsFile) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }

    public void addSixTarget(FilteredSixTarget target) {
        sixTargets.add(target);
        target.setOptionsFile(this);
    }

    public void removeSixTarget(FilteredSixTarget target) {
        sixTargets.remove(target);
        target.setOptionsFile(null);
    }

}
