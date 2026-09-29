package com.bnpp.regliss.six.entity;


import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;          // includes @Id (IDE showed "Cannot resolve symbol 'Id'"); use jakarta.persistence.* on Spring Boot 3

// TODO: re-add remaining project imports (Alt+Enter / Optimize Imports) for:
// AbstractSimpleEntity, ToStringAuditable, AuditedField, AuditedFieldCode, EqualityField

@Entity
@Table(name = "SIX_SUB_OR_FILTERS")
public class SixSubORFilters extends AbstractSimpleEntity implements ToStringAuditable {

    @Id
    @Getter @Setter
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Getter @Setter
    @Column(name = "SIX_SUB_OR_INDEX")
    private Integer sixORIndex;

    @Column(name = "FIELD_NAME")
    @AuditedField(AuditedFieldCode.FILTER_FIELD_NAME)
    @EqualityField
    @Getter @Setter
    private String fieldName;

    @Column(name = "OPERATOR")
    @AuditedField(AuditedFieldCode.FILTER_OPERATOR)
    @EqualityField
    @Getter @Setter
    private String operator;

    @Column(name = "FILTER_VALUE")
    @AuditedField(AuditedFieldCode.FILTER_VALUE)
    @EqualityField
    @Getter @Setter
    private String filterValue;

    @Column(name = "IS_DELETED")
    @EqualityField
    @Getter @Setter
    private Boolean deleted = false;

    @Getter @Setter
    @ManyToOne
    @JoinColumn(name = "SIX_SUBFILTER_ID")
    private SixSubFilters sixSubFilter;

    public SixSubORFilters() {
    }

    public SixSubORFilters(SixSubORFilters other) {
        this.id = other.id;
        this.sixORIndex = other.sixORIndex;
        this.fieldName = other.fieldName;
        this.operator = other.operator;
        this.filterValue = other.filterValue;
        this.deleted = other.deleted;
    }

    public boolean isNotDeleted() { return !deleted; }

}
