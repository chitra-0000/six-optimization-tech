package com.bnpp.regliss.importer.six.service;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

public class SixXmlRecordStreamTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** Simple record bound by Jackson. */
    public static class Rec {
        public String isin;
        public String name;
    }

    private File xml(String content) throws Exception {
        File f = tmp.newFile();
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private static final String THREE_RECORDS = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<ROOT><HEADER><isin>not a record</isin></HEADER>"
            + "<REC><isin>CH1</isin><name>one</name></REC>"
            + "<REC><isin>CH2</isin><name>two</name></REC>"
            + "<OTHER><isin>x</isin></OTHER>"
            + "<REC><isin>CH3</isin><name>three</name></REC></ROOT>";

    @Test
    public void constructorDisablesDtdAndExternalEntities() {
        XmlMapper mapper = new XmlMapper();
        new SixXmlRecordStream(mapper);
        XMLInputFactory factory = mapper.getFactory().getXMLInputFactory();
        assertEquals(Boolean.FALSE, factory.getProperty(XMLInputFactory.SUPPORT_DTD));
        assertEquals(Boolean.FALSE, factory.getProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES));
    }

    @Test
    public void countCountsOnlyTheRecordElement() throws Exception {
        SixXmlRecordStream stream = new SixXmlRecordStream(new XmlMapper());
        assertEquals(3, stream.count(xml(THREE_RECORDS), "REC"));
        assertEquals(0, stream.count(xml(THREE_RECORDS), "MISSING"));
    }

    @Test
    public void forEachBindsEveryRecordInOrder() throws Exception {
        SixXmlRecordStream stream = new SixXmlRecordStream(new XmlMapper());
        List<Rec> records = new ArrayList<>();
        int read = stream.forEach(xml(THREE_RECORDS), "REC", Rec.class, records::add);
        assertEquals(3, read);
        assertEquals(3, records.size());
        assertEquals("CH1", records.get(0).isin);
        assertEquals("two", records.get(1).name);
        assertEquals("CH3", records.get(2).isin);
    }

    @Test
    public void forEachWithoutRecordsCallsNothing() throws Exception {
        SixXmlRecordStream stream = new SixXmlRecordStream(new XmlMapper());
        List<Rec> records = new ArrayList<>();
        assertEquals(0, stream.forEach(xml("<ROOT/>"), "REC", Rec.class, records::add));
        assertTrue(records.isEmpty());
    }

    @Test
    public void nullRecordIsCountedButNotHandedOver() throws Exception {
        XmlMapper mapper = spy(new XmlMapper());
        doReturn(null).when(mapper).readValue(any(XMLStreamReader.class), eq(Rec.class));
        SixXmlRecordStream stream = new SixXmlRecordStream(mapper);
        List<Rec> records = new ArrayList<>();
        int read = stream.forEach(xml("<ROOT><REC/></ROOT>"), "REC", Rec.class, records::add);
        assertEquals(1, read);
        assertTrue(records.isEmpty());
    }
}
