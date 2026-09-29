package com.bnpp.regliss.importer.six.service;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

import java.util.List;

// TODO: re-add project import (Alt+Enter / Optimize Imports) for: OptionsFileDto

public class OptionsFilesWrapper {
@JacksonXmlProperty(localName = "INSTRUMENT_FILE")
@JacksonXmlElementWrapper(useWrapping = false)
private List<OptionsFileDto> optionsFile;

    public List<OptionsFileDto> getOptionsFile() { return optionsFile; }
}
