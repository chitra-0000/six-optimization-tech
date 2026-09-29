package com.bnpp.regliss.importer.six.service;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

import java.util.List;

// TODO: re-add project import (Alt+Enter / Optimize Imports) for: StructuredFileDto

@JacksonXmlRootElement(localName = "STRUCTURED_FILES")
public class StructuredFilesWrapper {

    @JacksonXmlProperty(localName = "STRUCTURED_FILE")
    @JacksonXmlElementWrapper(useWrapping = false)
    private List<StructuredFileDto> structuredFiles;

    public List<StructuredFileDto> getStructuredFiles() { return structuredFiles; }
}
