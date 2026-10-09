// TODO: lines 1-7 (generated-file header) and the class Javadoc with the XSD fragment are folded/not shown in the photos - copy them from the IDE if needed

package com.bnpp.regliss.six.generated;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.xml.bind.annotation.*;          // jakarta.xml.bind.annotation.* on Spring Boot 3
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;   // jakarta... on Spring Boot 3

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "relationType", propOrder = {
    "category",
    "ex",
    "name",
    "relationType",
    "type"
})
@Builder(toBuilder = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class RelationType {

    @XmlElement(required = true)
    protected String category;
    @XmlJavaTypeAdapter(BooleanIntAdapter.class)
    protected Boolean ex;
    @XmlElement(required = true)
    protected String name;
    @XmlElement(required = true)
    protected String relationType;
    @XmlElement(required = true)
    protected String type;
    @XmlAttribute(name = "id")
    protected String id;
    public String getCategory() {
        return category;
    }
    public void setCategory(String value) {
        this.category = value;
    }
    public void setEx(Boolean value) {
        this.ex = value;
    }
    public String getName() {
        return name;
    }
    public void setName(String value) {
        this.name = value;
    }
    public String getRelationType() {
        return relationType;
    }
    public void setRelationType(String value) {
        this.relationType = value;
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
