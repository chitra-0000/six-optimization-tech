package com.bnpp.regliss.six.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;          // jakarta.persistence.* on Spring Boot 3
import java.util.Objects;

@Entity
@Table(name = "SIX_FIELD_TYPE_FILTER")
public class SixFieldTypeFilter extends AbstractSimpleEntity {

    @Id
    @Getter
    @Setter
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    protected Long id;

    @Getter @Setter
    @Column(name = "FILTER_TYPE")
    private String filterType;

    @Getter @Setter
    @Column(name = "FIELD_NAME")
    private String fieldName;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        SixFieldTypeFilter that = (SixFieldTypeFilter) o;
        return Objects.equals(id, that.id) && Objects.equals(filterType, that.filterType) && Objects.equals(fieldName, that.fieldName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), id, filterType, fieldName);
    }
}
