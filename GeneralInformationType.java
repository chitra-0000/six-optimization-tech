package com.bnpp.regliss.six.generated;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.xml.bind.annotation.*;          // jakarta.xml.bind.annotation.* on Spring Boot 3

@XmlRootElement(name = "generalInformation")        // <-- optional, but allows direct marshaling
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "generalInformationType", propOrder = {
        "fullName",
        "type",
        "searchCode",
        "description",
        "instruction",
        "comments",
        "notes",
        "programs",
        "sanctions",
        "sources"
})
@Builder(toBuilder = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class GeneralInformationType {

    @XmlElement(required = true) protected String fullName;
    @XmlElement(required = true) protected String type;
    @XmlElement(required = true) protected String searchCode;
    @XmlElement(required = true) protected String description;
    @XmlElement(required = true) protected String instruction;
    @XmlElement(required = true) protected String comments;
    @XmlElement(required = true) protected String notes;
    @XmlElement(required = true) protected ProgramsType programs;
    @XmlElement(required = true) protected SanctionsType sanctions;
    @XmlElement(required = true) protected SourcesType sources;

    @XmlTransient
    public static class GeneralInformationTypeBuilder { }
}
