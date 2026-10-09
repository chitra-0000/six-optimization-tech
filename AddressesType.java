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
@XmlType(name = "addressesType", propOrder = {
    "address"
})
@Builder(toBuilder = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AddressesType {

    protected List<AddressType> address;

    public List<AddressType> getAddress() {
        if (address == null) {
            address = new ArrayList<AddressType>();
        }
        return this.address;
    }

}
