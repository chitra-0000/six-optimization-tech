package com.bnpp.regliss.six.entity;


import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;          // includes @Id (IDE showed "Cannot resolve symbol 'Id'"); use jakarta.persistence.* on Spring Boot 3
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static java.util.stream.Collectors.toSet;

// TODO: re-add remaining project imports (Alt+Enter / Optimize Imports) for:
// AbstractSimpleEntity, ToStringAuditable, AuditedField, AuditedFieldCode, EqualityField,
// AuditedCollection, AuditedAction, SixSubORFilters

@Entity
@Table(name = "SIX_SUB_FILTERS")
public class SixSubFilters extends AbstractSimpleEntity implements ToStringAuditable {

    @Id
    @Getter @Setter
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Getter @Setter
    @Column(name = "SIX_SUBFILTER_INDEX")
    private Integer sixIndex;

    @Column(name = "SIX_SUBFILTER_NAME")
    @AuditedField(AuditedFieldCode.SUBFILTER_NAME)
    @EqualityField
    @Getter @Setter
    private String sixSubFilterName;

    @Column(name = "IS_DELETED")
    @EqualityField
    @Getter @Setter
    private Boolean deleted = false;

    @Column(name = "IS_NESTED_FILTER")
    @EqualityField
    @Getter @Setter
    private Boolean nestedFilterStatus = false;

    @Getter @Setter
    @ManyToOne
    @JoinColumn(name = "SIX_FILTER_ID")
    private SixFilters sixFilter;

    @AuditedCollection(createAction = AuditedAction.LIST_FILTER_SUBFILTER_ADDITION,
            updateAction = AuditedAction.LIST_FILTER_SUBFILTER_MODIFICATION,
            deleteAction = AuditedAction.LIST_FILTER_SUBFILTER_DELETION)
    @OneToMany(mappedBy = "sixSubFilter", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<SixSubORFilters> sixSubORFilters = new HashSet<>();

    public SixSubFilters() {
    }

    public SixSubFilters(SixSubFilters other) {
        this.id = other.id;
        this.sixSubFilterName = other.sixSubFilterName;
        this.deleted = other.deleted;
        this.sixIndex = other.sixIndex;
        this.nestedFilterStatus = other.nestedFilterStatus;
        for (SixSubORFilters otherSixSubORFilter : other.sixSubORFilters) {
            addSixSubORfilter(otherSixSubORFilter);
        }
    }

    public boolean isNotDeleted() { return !deleted; }

    @Override
    public String toStringAudit() { return this.sixIndex + " / "+ super.toStringAudit(); }

    public void addSixSubORfilter(SixSubORFilters sixSubORFilter) {
        sixSubORFilter.setSixSubFilter(this);
        this.sixSubORFilters.add(sixSubORFilter);
        if (sixSubORFilter.getSixORIndex() == null) {
            sixSubORFilter.setSixORIndex(getNextSubfilterIndex());
        }
    }

    public Integer getNextSubfilterIndex() {
        return 1 + sixSubORFilters.stream()
                .map(SixSubORFilters::getSixORIndex)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
    }

    public void updateSixSubORfilter(SixSubORFilters sixSubORFilter) { this.sixSubORFilters.add(sixSubORFilter); }

    public void removeSixSubORfilter(SixSubORFilters sixSubORFilter) { sixSubORFilter.setDeleted(true); }


    public Set<SixSubORFilters> getActiveSixSubORfilters() {
        return Collections.unmodifiableSet(this.sixSubORFilters.stream()
                .filter(SixSubORFilters::isNotDeleted)
                .collect(toSet()));
    }

    public Set<SixSubORFilters> getSixSubORfilters() { return Collections.unmodifiableSet(this.sixSubORFilters); }

    public void updateSixSubORfilters(List<SixSubORFilters> listSixSubORFilters) {
        this.sixSubORFilters.clear();
        for (SixSubORFilters sixSubORFilter : listSixSubORFilters) {
            SixSubORFilters filter = new SixSubORFilters();
            filter.setFieldName(sixSubORFilter.getFieldName());
            filter.setOperator(sixSubORFilter.getOperator());
            filter.setFilterValue(sixSubORFilter.getFilterValue());
            filter.setDeleted(sixSubORFilter.getDeleted());
            if (sixSubORFilter.getSixORIndex() == null) {
                filter.setSixORIndex(getNextSubfilterIndex());
            } else {
                filter.setSixORIndex(sixSubORFilter.getSixORIndex());
            }
            filter.setSixSubFilter(this);
            this.sixSubORFilters.add(filter);
        }
    }

    public void updateAuditSixSubORfilters(List<SixSubORFilters> listSixSubORFilters) {
        this.sixSubORFilters.clear();
        for (SixSubORFilters sixSubORFilter : listSixSubORFilters) {
            SixSubORFilters filter = new SixSubORFilters();
            filter.setFieldName(sixSubORFilter.getFieldName());
            filter.setOperator(sixSubORFilter.getOperator());
            filter.setFilterValue(sixSubORFilter.getFilterValue());
            filter.setDeleted(sixSubORFilter.getDeleted());
            filter.setId(sixSubORFilter.getId());
            if (sixSubORFilter.getSixORIndex() == null) {
                filter.setSixORIndex(getNextSubfilterIndex());
            } else {
                filter.setSixORIndex(sixSubORFilter.getSixORIndex());
            }
            filter.setSixSubFilter(this);
            this.sixSubORFilters.add(filter);
        }
    }

}
