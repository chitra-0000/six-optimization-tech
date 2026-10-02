package com.bnpp.regliss.importer.six.service;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

/**
 * PERF: reads the XSLT output one record at a time instead of loading the
 * whole document into a String and then into one giant List of DTOs.
 *
 * Before: file -> String (hundreds of MB, UTF-16 in the heap) -> full Jackson
 * tree -> List of 1 lakh DTOs -> List of entities, all alive at the same time.
 * That is what pushed the 16 GB heap into constant GC.
 *
 * Now only the DTOs of the batches currently being written are in memory.
 */
public final class SixXmlRecordStream {

    /** Called for every record element found in the file. */
    public interface RecordHandler<D> {
        void onRecord(D dto);
    }

    private final XmlMapper xmlMapper;

    public SixXmlRecordStream(XmlMapper xmlMapper) {
        this.xmlMapper = xmlMapper;
        // Security (XXE, Fortify "XML External Entity Injection"): never resolve a DTD or an external
        // entity while reading the file. Jackson's defaults already do this; set explicitly so it
        // cannot change with a library upgrade and so scanners can see it.
        XMLInputFactory factory = inputFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
    }

    /** Fast first pass: counts record elements without binding them (needed for the progress %). */
    public int count(File xml, String recordElement) throws IOException, XMLStreamException {
        int count = 0;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(xml.toPath()), 1 << 16)) {
            XMLStreamReader reader = inputFactory().createXMLStreamReader(in);
            try {
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT
                            && recordElement.equals(reader.getLocalName())) {
                        count++;
                    }
                }
            } finally {
                reader.close();
            }
        }
        return count;
    }

    /** Binds each record element to {@code dtoClass} and hands it to {@code handler}. */
    public <D> int forEach(File xml, String recordElement, Class<D> dtoClass, RecordHandler<D> handler)
            throws IOException, XMLStreamException {
        int read = 0;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(xml.toPath()), 1 << 16)) {
            XMLStreamReader reader = inputFactory().createXMLStreamReader(in);
            try {
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT
                            && recordElement.equals(reader.getLocalName())) {
                        // Jackson consumes exactly this element and leaves the
                        // reader on its END_ELEMENT, so the loop continues cleanly.
                        D dto = xmlMapper.readValue(reader, dtoClass);
                        read++;
                        if (dto != null) {
                            handler.onRecord(dto);
                        }
                    }
                }
            } finally {
                reader.close();
            }
        }
        return read;
    }

    private XMLInputFactory inputFactory() {
        // Same (Woodstox) factory Jackson uses internally; DTD and external entities disabled in the constructor.
        return xmlMapper.getFactory().getXMLInputFactory();
    }
}
