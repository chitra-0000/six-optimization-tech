// TODO: lines 1-7 (generated-file header) and the class Javadoc with the XSD fragment are folded/not shown in the photos - copy them from the IDE if needed

package com.bnpp.regliss.six.generated;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.xml.bind.annotation.*;          // jakarta.xml.bind.annotation.* on Spring Boot 3
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;   // jakarta... on Spring Boot 3

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "dateType", propOrder = {
    "isPrimary",
    "type",
    "dateValue",
    "placeValue",
    "country",
    "dateAdditionalInfo"
})
@Builder(toBuilder = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class DateType {

    @XmlJavaTypeAdapter(BooleanIntAdapter.class)
    protected Boolean isPrimary;
    @XmlElement(required = true)
    protected String type;
    @XmlElement(required = true)
    protected String dateValue;
    @XmlElement(required = true)
    protected String placeValue;
    @XmlElement(required = true)
    protected String country;
    @XmlElement(required = true)
    protected String dateAdditionalInfo;
    @XmlAttribute(name = "id")
    protected String id;
    public Boolean isIsPrimary() {
        return isPrimary;
    }
    public void setIsPrimary(Boolean value) {
        this.isPrimary = value;
    }
    public String getType() {
        return type;
    }
    public void setType(String value) {
        this.type = value;
    }
    public String getDateValue() {
        return dateValue;
    }
    public void setDateValue(String value) {
        this.dateValue = value;
    }
    public String getPlaceValue() {
        return placeValue;
    }
    public void setPlaceValue(String value) {
        this.placeValue = value;
    }
    public String getCountry() {
        return country;
    }
    public void setCountry(String value) {
        this.country = value;
    }
    public String getDateAdditionalInfo() {
        return dateAdditionalInfo;
    }
    public void setDateAdditionalInfo(String value) {
        this.dateAdditionalInfo = value;
    }
    public String getId() {
        return id;
    }
    public void setId(String value) {
        this.id = value;
    }
}
