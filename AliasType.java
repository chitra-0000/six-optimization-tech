// TODO: lines 1-7 (generated-file header) and the class Javadoc with the XSD fragment are folded/not shown in the photos - copy them from the IDE if needed

package com.bnpp.regliss.six.generated;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.xml.bind.annotation.*;          // jakarta.xml.bind.annotation.* on Spring Boot 3

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "aliasType", propOrder = {
    "referenceAliasReglissV3",
    "title",
    "suffix",
    "firstName",
    "middleName",
    "lastName",
    "quality",
    "type",
    "language"
})
@Builder(toBuilder = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AliasType {

    protected String referenceAliasReglissV3;
    @XmlElement(required = true)
    protected String title;
    @XmlElement(required = true)
    protected String suffix;
    @XmlElement(required = true)
    protected String firstName;
    @XmlElement(required = true)
    protected String middleName;
    @XmlElement(required = true)
    protected String lastName;
    @XmlElement(required = true)
    protected String quality;
    @XmlElement(required = true)
    protected String type;
    @XmlElement(required = true)
    protected String language;
    @XmlAttribute(name = "externalReference")
    protected String externalReference;
    public String getReferenceAliasReglissV3() {
        return referenceAliasReglissV3;
    }
    public void setReferenceAliasReglissV3(String value) {
        this.referenceAliasReglissV3 = value;
    }
    public String getTitle() {
        return title;
    }
    public void setTitle(String value) {
        this.title = value;
    }
    public String getSuffix() {
        return suffix;
    }
    public void setSuffix(String value) {
        this.suffix = value;
    }
    public String getFirstName() {
        return firstName;
    }
    public void setFirstName(String value) {
        this.firstName = value;
    }
    public String getMiddleName() {
        return middleName;
    }
    public void setMiddleName(String value) {
        this.middleName = value;
    }
    public String getLastName() {
        return lastName;
    }
    public void setLastName(String value) {
        this.lastName = value;
    }
    public String getQuality() {
        return quality;
    }
    public void setQuality(String value) {
        this.quality = value;
    }
    public String getType() {
        return type;
    }
    public void setType(String value) {
        this.type = value;
    }
    public String getLanguage() {
        return language;
    }
    public void setLanguage(String value) {
        this.language = value;
    }
    public String getExternalReference() {
        return externalReference;
    }
    public void setExternalReference(String value) {
        this.externalReference = value;
    }


}
