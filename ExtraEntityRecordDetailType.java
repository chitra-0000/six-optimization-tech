package com.bnpp.regliss.six.generated;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.xml.bind.annotation.*;          // jakarta.xml.bind.annotation.* on Spring Boot 3

@XmlRootElement(name = "extraEntityRecordDetail") // optional, but handy
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "extraEntityRecordDetailType", propOrder = {
        "addresses",
        "identityDocuments",
        "countryDetails",
        "datesDetails",
        "aliases",
        "codes",
        "relations"
})
@Builder(toBuilder = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ExtraEntityRecordDetailType {

    @XmlElement(required = true) protected AddressesType addresses;
    @XmlElement(required = true) protected IdentityDocumentsType identityDocuments;
    @XmlElement(required = true) protected CountryDetailsType countryDetails;
    @XmlElement(required = true) protected DatesDetailsType datesDetails;
    @XmlElement(required = true) protected AliasesType aliases;
    @XmlElement(required = true) protected CodesType codes;
    @XmlElement(required = true) protected RelationsType relations;

    @XmlTransient
    public static class ExtraEntityRecordDetailTypeBuilder { }
}
