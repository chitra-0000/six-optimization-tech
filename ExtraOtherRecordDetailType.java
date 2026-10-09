// TODO: lines 1-7 (generated-file header) and the class Javadoc with the XSD fragment are folded/not shown in the photos - copy them from the IDE if needed

package com.bnpp.regliss.six.generated;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.xml.bind.annotation.*;          // jakarta.xml.bind.annotation.* on Spring Boot 3

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "extraOtherRecordDetailType", propOrder = {
    "aliases",
    "category"
})
@Builder(toBuilder = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ExtraOtherRecordDetailType {

    @XmlElement(required = true)
    protected AliasesType aliases;
    @XmlElement(required = true)
    protected String category;
    public AliasesType getAliases() {
        return aliases;
    }
    public void setAliases(AliasesType value) {
        this.aliases = value;
    }
    public String getCategory() {
        return category;
    }
    public void setCategory(String value) {
        this.category = value;
    }

}
