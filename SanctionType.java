// TODO: lines 1-7 are a folded generated-file header comment (/.../) in the IDE - copy it from the IDE if needed

package com.bnpp.regliss.six.generated;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.xml.bind.annotation.*;          // jakarta.xml.bind.annotation.* on Spring Boot 3

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "sanctionType", propOrder = {
    "value"
})
@Builder(toBuilder = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SanctionType {

    @XmlValue
    protected String value;
    @XmlAttribute(name = "code")
    protected String code;
    @XmlAttribute(name = "endDate")
    protected String endDate;
    @XmlAttribute(name = "id")
    protected String id;
    @XmlAttribute(name = "sinceDate")
    protected String sinceDate;
    public String getValue() {
        return value;
    }
    public void setValue(String value) {
        this.value = value;
    }
    public String getId() {
        return id;
    }
    public void setId(String value) {
        this.id = value;
    }
    public String getEndDate() {
        return endDate;
    }
    public void setEndDate(String value) {
        this.endDate = value;
    }
    public String getSinceDate() {
        return sinceDate;
    }
    public void setSinceDate(String value) {
        this.sinceDate = value;
    }
    public String getCode() {
        return code;
    }
    public void setCode(String value) {
        this.code = value;
    }

}
