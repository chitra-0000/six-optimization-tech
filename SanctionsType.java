// TODO: lines 1-7 are a folded generated-file header comment (/.../) in the IDE - copy it from the IDE if needed

package com.bnpp.regliss.six.generated;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.xml.bind.annotation.*;          // jakarta.xml.bind.annotation.* on Spring Boot 3
import java.util.ArrayList;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "sanctionsType", propOrder = {
    "sanction"
})
@Builder(toBuilder = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SanctionsType {

    protected List<SanctionType> sanction;

    public List<SanctionType> getSanction() {
        if (sanction == null) {
            sanction = new ArrayList<>();
        }
        return this.sanction;
    }

}
