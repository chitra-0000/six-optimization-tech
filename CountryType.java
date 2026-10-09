// TODO: lines 1-7 (generated-file header) and the class Javadoc with the XSD fragment are folded/not shown in the photos - copy them from the IDE if needed

package com.bnpp.regliss.six.generated;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.xml.bind.annotation.*;          // jakarta.xml.bind.annotation.* on Spring Boot 3
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;   // jakarta... on Spring Boot 3

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "countryType", propOrder = {
    "additionalInfo",
    "country",
    "principal",
    "type"
})
@Builder(toBuilder = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CountryType {

    @XmlElement(required = true)
    protected String additionalInfo;
    @XmlElement(required = true)
    protected String country;
    @XmlJavaTypeAdapter(BooleanIntAdapter.class)
    protected Boolean principal;
    @XmlElement(required = true)
    protected String type;
    @XmlAttribute(name = "id")
    protected String id;
    public String getAdditionalInfo() {
        return additionalInfo;
    }
    public void setAdditionalInfo(String value) {
        this.additionalInfo = value;
    }
    public String getCountry() {
        return country;
    }
    public void setCountry(String value) {
        this.country = value;
    }
    public Boolean isPrincipal() {
        return principal;
    }
    public void setPrincipal(Boolean value) {
        this.principal = value;
    }
    public String getType() {
        return type;
    }
    public void setType(String value) {
        this.type = value;
    }
    public String getId() {
        return id;
    }
    public void setId(String value) {
        this.id = value;
    }
}
