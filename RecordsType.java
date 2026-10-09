package com.bnpp.regliss.six.generated;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.xml.bind.annotation.*;          // jakarta.xml.bind.annotation.* on Spring Boot 3
import java.util.ArrayList;
import java.util.List;

@XmlRootElement(name = "records")
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "recordsType", propOrder = {"recordObj"})
@Data
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
public class RecordsType {

    @XmlElement(name = "record", required = true)
    private List<RecordType> recordObj = new ArrayList<>();

    @XmlTransient
    public static class RecordsTypeBuilder { /* generated - empty */ }
}
