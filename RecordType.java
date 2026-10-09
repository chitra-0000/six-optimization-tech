// TODO: lines 1-7 are a folded generated-file header comment (/.../) in the IDE - copy it from the IDE if needed

package com.bnpp.regliss.six.generated;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.xml.bind.annotation.*;          // jakarta.xml.bind.annotation.* on Spring Boot 3

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "recordType", propOrder = {
    "generalInformation",
    "extraEntityRecordDetail",
    "extraOtherRecordDetail"
})
@Builder(toBuilder = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class RecordType {

    @XmlElement(required = true)
    protected GeneralInformationType generalInformation;
    protected ExtraEntityRecordDetailType extraEntityRecordDetail;
    protected ExtraOtherRecordDetailType extraOtherRecordDetail;
    @XmlAttribute(name = "applicationDate")
    protected String applicationDate;
    @XmlAttribute(name = "deletionDate")
    protected String deletionDate;
    @XmlAttribute(name = "externalReference")
    protected String externalReference;

    /**
     * Gets the value of the generalInformation property.
     *
     * @return
     *     possible object is
     *     {@link GeneralInformationType }
     *
     */
    public GeneralInformationType getGeneralInformation() {
        return generalInformation;
    }

    /**
     * Sets the value of the generalInformation property.
     *
     * @param value
     *     allowed object is
     *     {@link GeneralInformationType }
     *
     */
    public void setGeneralInformation(GeneralInformationType value) {
        this.generalInformation = value;
    }

    /**
     * Gets the value of the extraEntityRecordDetail property.
     *
     * @return
     *     possible object is
     *     {@link ExtraEntityRecordDetailType }
     *
     */
    public ExtraEntityRecordDetailType getExtraEntityRecordDetail() {
        return extraEntityRecordDetail;
    }

    /**
     * Sets the value of the extraEntityRecordDetail property.
     *
     * @param value
     *     allowed object is
     *     {@link ExtraEntityRecordDetailType }
     *
     */
    public void setExtraEntityRecordDetail(ExtraEntityRecordDetailType value) {
        this.extraEntityRecordDetail = value;
    }

    /**
     * Gets the value of the extraOtherRecordDetail property.
     *
     * @return
     *     possible object is
     *     {@link ExtraOtherRecordDetailType }
     *
     */
    public ExtraOtherRecordDetailType getExtraOtherRecordDetail() {
        return extraOtherRecordDetail;
    }

    /**
     * Sets the value of the extraOtherRecordDetail property.
     *
     * @param value
     *     allowed object is
     *     {@link ExtraOtherRecordDetailType }
     *
     */

    /**
     * Gets the value of the applicationDate property.
     *
     * @return
     *     possible object is
     *     {@link String }
     *
     */
    public String getApplicationDate() {
        return applicationDate;
    }

    /**
     * Sets the value of the applicationDate property.
     *
     * @param value
     *     allowed object is
     *     {@link String }
     *
     */
    public void setApplicationDate(String value) {
        this.applicationDate = value;
    }

    /**
     * Gets the value of the deletionDate property.
     *
     * @return
     *     possible object is
     *     {@link String }
     *
     */
    public String getDeletionDate() {
        return deletionDate;
    }

    /**
     * Sets the value of the deletionDate property.
     *
     * @param value
     *     allowed object is
     *     {@link String }
     *
     */
    public void setDeletionDate(String value) {
        this.deletionDate = value;
    }

    /**
     * Gets the value of the externalReference property.
     *
     * @return
     *     possible object is
     *     {@link String }
     *
     */
    public String getExternalReference() {
        return externalReference;
    }

    /**
     * Sets the value of the externalReference property.
     *
     * @param value
     *     allowed object is
     *     {@link String }
     *
     */
    public void setExternalReference(String value) {
        this.externalReference = value;
    }

}
