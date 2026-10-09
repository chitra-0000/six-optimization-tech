package com.bnpp.regliss.six.generated;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.xml.bind.annotation.*;          // jakarta.xml.bind.annotation.* on Spring Boot 3

/**
 * JAXB-ready version of the XSD-generated ListType.
 * All Lombok-generated builder classes are hidden from JAXB.
 */
@XmlRootElement(name = "list")
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "listType", propOrder = {
        "applicationDate",
        "juridictionBase",
        "label",
        "sourceVersion",
        "type",
        "records"
})
@Data
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
public class ListType {

    @XmlElement(required = true)
    @XmlSchemaType(name = "date")
    protected String applicationDate;

    @XmlElement(required = true)
    protected String juridictionBase;

    @XmlElement(required = true)
    protected String label;

    @XmlElement(required = true)
    protected String sourceVersion;

    @XmlElement(required = true)
    protected String type;

    @XmlElement(required = true)
    protected RecordsType records;

    /** Hide Lombok's static builder from JAXB */
    @XmlTransient
    public static class ListTypeBuilder { /* generated - empty */ }
}
