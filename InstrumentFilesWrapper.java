package com.bnpp.regliss.importer.six.service;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

import java.util.List;

// TODO: re-add project import (Alt+Enter / Optimize Imports) for: InstrumentFilesDto

@JacksonXmlRootElement(localName = "INSTRUMENT_FILES")
public class InstrumentFilesWrapper {

    @JacksonXmlProperty(localName = "INSTRUMENT_FILE")
    @JacksonXmlElementWrapper(useWrapping = false)
    private List<InstrumentFilesDto> instrumentFiles;

    public List<InstrumentFilesDto> getInstrumentFiles() { return instrumentFiles; }
}
