package com.bnpp.regliss.six.entity;


import com.bnpp.regliss.entity.ReglissList;
import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;          // includes @Id (IDE showed "Cannot resolve symbol 'Id'"); use jakarta.persistence.* on Spring Boot 3
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import static java.util.stream.Collectors.toSet;

// TODO: re-add remaining project imports (Alt+Enter / Optimize Imports) for:
// AbstractSimpleEntity, ToStringAuditable, AuditedField, AuditedFieldCode, EqualityField,
// AuditedCollection, AuditedAction, SixSubFilters

@Entity
@Table(name = "SIX_FILTERS")
public class SixFilters extends AbstractSimpleEntity implements ToStringAuditable {

    @Id
    @Getter @Setter
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "FILTER_NAME")
    @AuditedField(AuditedFieldCode.FILTER_NAME)
    @EqualityField
    @Getter @Setter
    private String filterName;

    @Column(name = "FILTER_TAG")
    @AuditedField(AuditedFieldCode.FILTER_TAG)
    @EqualityField
    @Getter @Setter
    private String filterTag;

    @Column(name = "FILE_TYPE")
    @AuditedField(AuditedFieldCode.FILTER_FILE_TYPE)
    @EqualityField
    @Getter @Setter
    private String fileType;

    @JoinColumn(name = "LIST_ID")
    @EqualityField
    @ManyToOne
    @Getter @Setter
    private ReglissList list;

    @Column(name = "IS_ACTIVE")
    @EqualityField
    @Getter @Setter
    private boolean active;

    @Column(name = "IS_DELETED")
    @AuditedField(AuditedFieldCode.FILTER_DELETE)
    @EqualityField
    @Getter @Setter
    private boolean deleted = false;

    @Column(name = "EXCLUDE_CMIC")
    @AuditedField(AuditedFieldCode.ADD_EXCLUDE_CMIC)
    @EqualityField
    @Getter @Setter
    private boolean excludeCMIC = false;

    @Column(name = "EXCLUDE_E014071")
    @AuditedField(AuditedFieldCode.ADD_EXCLUDE_E014071)
    @EqualityField
    @Getter @Setter
    private boolean excludeE014071 = false;

    @AuditedCollection(createAction = AuditedAction.LIST_FILTER_SUBFILTER_ADDITION,
            updateAction = AuditedAction.LIST_FILTER_SUBFILTER_MODIFICATION,
            deleteAction = AuditedAction.LIST_FILTER_SUBFILTER_DELETION)
    @OneToMany(mappedBy = "sixFilter", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<SixSubFilters> sixSubFilters = new HashSet<>();

    public SixFilters() {
    }

    public SixFilters(SixFilters other) {
        this.id = other.id;
        this.filterName = other.filterName;
        this.filterTag = other.filterTag;
        this.fileType = other.fileType;
        this.active = other.active;
        this.deleted = other.deleted;
        this.excludeCMIC = other.excludeCMIC;
        this.excludeE014071 = other.excludeE014071;
        for (SixSubFilters otherSixSubFilter : other.sixSubFilters) {
            addSixSubfilter(new SixSubFilters(otherSixSubFilter));
        }
    }

    public void addSixSubfilter(SixSubFilters sixSubFilter) {
        sixSubFilter.setSixFilter(this);
        this.sixSubFilters.add(sixSubFilter);
        if (sixSubFilter.getSixIndex() == null) {
            sixSubFilter.setSixIndex(getNextSubfilterIndex());
        }
    }

    public Integer getNextSubfilterIndex() {
        return 1 + sixSubFilters.stream()
                .map(SixSubFilters::getSixIndex)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
    }

    public void updateSixSubfilter(SixSubFilters sixSubFilter) { this.sixSubFilters.add(sixSubFilter); }

    public void removeSixSubfilter(SixSubFilters sixSubFilter) { sixSubFilter.setDeleted(true); }


    public Set<SixSubFilters> getActiveSixSubfilters() {
        return Collections.unmodifiableSet(this.sixSubFilters.stream()
                .filter(SixSubFilters::isNotDeleted)
                .collect(toSet()));
    }

    public Set<SixSubFilters> getSixSubfilters() { return Collections.unmodifiableSet(this.sixSubFilters); }

    public boolean isNotActive() { return !active; }

    @Override
    public String toStringAudit() { return filterName; }

}
