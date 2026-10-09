// TODO: lines 1-7 (generated-file header) and the class Javadoc with the XSD fragment are folded/not shown in the photos - copy them from the IDE if needed

package com.bnpp.regliss.six.generated;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.xml.bind.annotation.*;          // jakarta.xml.bind.annotation.* on Spring Boot 3
import java.util.Objects;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "documentType", propOrder = {
    "type",
    "issueDate",
    "expirationDate",
    "country",
    "additionalInfo",
    "number"
})
@Builder(toBuilder = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class DocumentType {

    @XmlElement(required = true)
    protected String type;
    @XmlElement(required = true)
    protected String issueDate;
    @XmlElement(required = true)
    protected String expirationDate;
    @XmlElement(required = true)
    protected String country;
    @XmlElement(required = true)
    protected String additionalInfo;
    @XmlElement(required = true)
    protected String number;
    @XmlAttribute(name = "id")
    protected String id;
    public String getType() {
        return type;
    }
    public void setType(String value) {
        this.type = value;
    }
    public String getIssueDate() {
        return issueDate;
    }
    public void setIssueDate(String value) {
        this.issueDate = value;
    }
    public String getExpirationDate() {
        return expirationDate;
    }
    public void setExpirationDate(String value) {
        this.expirationDate = value;
    }
    public String getCountry() {
        return country;
    }
    public void setCountry(String value) {
        this.country = value;
    }
    public String getAdditionalInfo() {
        return additionalInfo;
    }
    public void setAdditionalInfo(String value) {
        this.additionalInfo = value;
    }
    public String getNumber() {
        return number;
    }
    public void setNumber(String value) {
        this.number = value;
    }
    public String getId() {
        return id;
    }
    public void setId(String value) {
        this.id = value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DocumentType)) return false;
        DocumentType that = (DocumentType) o;
        return Objects.equals(type, that.type) &&
                Objects.equals(issueDate, that.issueDate) &&
                Objects.equals(expirationDate, that.expirationDate) &&
                Objects.equals(country, that.country) &&
                Objects.equals(additionalInfo, that.additionalInfo) &&
                Objects.equals(number, that.number);

    }

    @Override
    public int hashCode() {
        return Objects.hash(type, issueDate, expirationDate, country, additionalInfo, number);
    }

}
