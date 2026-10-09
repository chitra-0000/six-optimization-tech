package com.bnpp.regliss.six.generated;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import javax.xml.bind.annotation.*;          // jakarta.xml.bind.annotation.* on Spring Boot 3
import java.util.Objects;
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "programType", propOrder = {
    "label",
    "type",
    "description"
})
@Builder(toBuilder = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ProgramType {
    @XmlElement(required = true)
    protected String label;
    @XmlElement(required = true)
    protected String type;
    @XmlElement(required = true)
    protected String description;
    @XmlAttribute(name = "id")
    protected String id;
    public String getLabel() {
        return label;
    }
    public void setLabel(String value) {
        this.label = value;
    }
    public String getType() {
        return type;
    }
    public void setType(String value) {
        this.type = value;
    }
    public String getDescription() {
        return description;
    }
    public void setDescription(String value) {
        this.description = value;
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
        if (!(o instanceof ProgramType)) return false;
        ProgramType that = (ProgramType) o;
        return Objects.equals(label, that.label) &&
                Objects.equals(type, that.type) &&
                Objects.equals(description, that.description);
    }
    @Override
    public int hashCode() {
        return Objects.hash(label, type, description);
    }
}
