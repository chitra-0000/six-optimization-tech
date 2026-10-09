// TODO: lines 1-7 (generated-file header) and the class Javadoc with the XSD fragment are folded/not shown in the photos - copy them from the IDE if needed

package com.bnpp.regliss.six.generated;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.xml.bind.annotation.*;          // jakarta.xml.bind.annotation.* on Spring Boot 3

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "addressType", propOrder = {
    "referenceAddressReglissV3",
    "address1",
    "address2",
    "city",
    "country",
    "remarks",
    "state",
    "zipCode"
})
@Builder(toBuilder = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AddressType {

    protected String referenceAddressReglissV3;
    @XmlElement(required = true)
    protected String address1;
    @XmlElement(required = true)
    protected String address2;
    @XmlElement(required = true)
    protected String city;
    @XmlElement(required = true)
    protected String country;
    @XmlElement(required = true)
    protected String remarks;
    @XmlElement(required = true)
    protected String state;
    @XmlElement(required = true)
    protected String zipCode;
    @XmlAttribute(name = "id")
    protected String id;
    public String getReferenceAddressReglissV3() {
        return referenceAddressReglissV3;
    }
    public void setReferenceAddressReglissV3(String value) {
        this.referenceAddressReglissV3 = value;
    }
    public String getAddress1() {
        return address1;
    }
    public void setAddress1(String value) {
        this.address1 = value;
    }
    public String getAddress2() {
        return address2;
    }
    public void setAddress2(String value) {
        this.address2 = value;
    }
    public String getCity() {
        return city;
    }
    public void setCity(String value) {
        this.city = value;
    }
    public String getCountry() {
        return country;
    }
    public void setCountry(String value) {
        this.country = value;
    }
    public String getRemarks() {
        return remarks;
    }
    public void setRemarks(String value) {
        this.remarks = value;
    }
    public String getState() {
        return state;
    }
    public void setState(String value) {
        this.state = value;
    }
    public String getZipCode() {
        return zipCode;
    }
    public void setZipCode(String value) {
        this.zipCode = value;
    }
    public String getId() {
        return id;
    }
    public void setId(String value) {
        this.id = value;
    }
}
