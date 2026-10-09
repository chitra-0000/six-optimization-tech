// TODO: lines 1-7 (generated-file header) and the class Javadoc with the XSD fragment are folded/not shown in the photos - copy them from the IDE if needed

package com.bnpp.regliss.six.generated;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.xml.bind.annotation.*;          // jakarta.xml.bind.annotation.* on Spring Boot 3
import java.util.ArrayList;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "relationsType", propOrder = {
    "relation"
})
@Builder(toBuilder = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class RelationsType {

    protected List<RelationType> relation;

    public List<RelationType> getRelation() {
        if (relation == null) {
            relation = new ArrayList<>();
        }
        return this.relation;
    }

}
