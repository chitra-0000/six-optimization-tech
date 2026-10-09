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
@XmlType(name = "sourcesType", propOrder = {
    "source"
})
@Builder(toBuilder = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SourcesType {

    protected List<String> source;

    public List<String> getSource() {
        if (source == null) {
            source = new ArrayList<>();
        }
        return this.source;
    }

}
