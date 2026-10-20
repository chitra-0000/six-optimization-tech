package com.bnpp.regliss.six.exporter.service;

import org.junit.After;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// TODO: re-add project imports for: ListType, SixFilters, SixSubFilters, SixSubORFilters

public class ExportFileWriterTest {

    @SuppressWarnings("unchecked")
    private static AtomicReference<JAXBContext> context() {
        return (AtomicReference<JAXBContext>) ReflectionTestUtils.getField(ExportFileWriter.class, "LIST_TYPE_CONTEXT");
    }

    @After
    public void resetContext() {
        context().set(null);
    }

    private static ListType listType() {
        return ListType.builder().applicationDate("2026-10-04").juridictionBase("A").label("UK").sourceVersion("2").type("FULL").build();
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static SixSubORFilters or(Integer index, String field, String operator, String value) {
        SixSubORFilters or = mock(SixSubORFilters.class);
        when(or.getSixORIndex()).thenReturn(index);
        when(or.getFieldName()).thenReturn(field);
        when(or.getOperator()).thenReturn(operator);
        when(or.getFilterValue()).thenReturn(value);
        return or;
    }

    private static SixSubFilters sub(Integer index, SixSubORFilters... ors) {
        SixSubFilters sub = mock(SixSubFilters.class);
        when(sub.getSixIndex()).thenReturn(index);
        when(sub.getActiveSixSubORfilters()).thenReturn(new LinkedHashSet<>(Arrays.asList(ors)));
        return sub;
    }

    private static SixFilters filter(String fileType, String name, boolean cmic, boolean e014071, SixSubFilters... subs) {
        SixFilters f = mock(SixFilters.class);
        when(f.getFileType()).thenReturn(fileType);
        when(f.getFilterName()).thenReturn(name);
        when(f.isExcludeCMIC()).thenReturn(cmic);
        when(f.isExcludeE014071()).thenReturn(e014071);
        Set<SixSubFilters> set = new LinkedHashSet<>(Arrays.asList(subs));
        when(f.getActiveSixSubfilters()).thenReturn(set);
        return f;
    }

    // ------------------------------------------------------------------ original footer

    @Test
    public void createFile_xmlAndOriginalFooter() throws Exception {
        String xml = read(ExportFileWriter.createFile(listType(), "462656", "PARM", "APAC", "v1.3", "A|B"));
        assertTrue(xml, xml.contains("<label>UK</label>"));
        assertTrue(xml, xml.contains("<!--     Content filter by [462656] on [PARM]  -->"));
        assertTrue(xml, xml.contains("<!--     with [APAC] - [v1.3]  -->"));
        assertTrue(xml, xml.contains("<!--          Refcode : A|B  -->"));

        String empty = read(ExportFileWriter.createFile(listType(), null, null, null, null, null));
        assertTrue(empty, empty.contains("<!--     Content filter by [] on []  -->"));
    }

    @Test
    public void createFile_nullObject_refused() throws Exception {
        try {
            ExportFileWriter.createFile(null, "u", "s", "p", "v", "r");
            fail();
        } catch (IllegalArgumentException e) {
            assertEquals("xmlObj must not be null", e.getMessage());
        }
    }

    @Test
    public void createFile_marshalError_wrapped() throws Exception {
        JAXBContext broken = mock(JAXBContext.class);
        when(broken.createMarshaller()).thenThrow(new JAXBException("no marshaller"));
        context().set(broken);
        ListType list = listType();
        try {
            ExportFileWriter.createFile(list, "u", "s", "p", "v", "r");
            fail();
        } catch (RuntimeException e) {
            assertEquals("Failed to marshal ListType to XML", e.getMessage());
            assertTrue(e.getCause() instanceof JAXBException);
        }
    }

    @Test
    public void writeFile_original_nullObject_refused() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ExportFileWriter.writeFile(null, out, "u", "s", "p", "v", "r");
            fail();
        } catch (IllegalArgumentException e) {
            assertEquals(0, out.size());
        }
    }

    // ------------------------------------------------------------------ SIX footer

    @Test
    public void writeFile_sixFooter_filtersInFileTypeOrder() throws Exception {
        SixFilters options = filter("Options File", "opt", false, false, sub(1, or(1, "A", "equals", "x")));
        SixFilters instrB = filter("Instrument File", "b filter", true, true,
                sub(2, or(2, "TARGET", "equals", "UK"), or(1, "TARGET", "equals", "EU"), or(null, "X", null, null)),
                sub(1, or(1, "SANCTIONED", "equals", "Yes")),
                sub(null));                                                       // no row: left out
        SixFilters instrA = filter(" INSTRUMENT FILE ", "A filter", false, false);
        SixFilters other = filter("something else", null, false, false, sub(null, or(1, "F", "is empty", null)));
        SixFilters noType = filter(null, "z", false, false);
        SixFilters structured = filter("Structured File", "s--name", false, false, sub(1, or(1, "N", "contains", "a\nb")));
        List<SixFilters> filters = Arrays.asList(options, instrB, null, other, instrA, noType, structured);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExportFileWriter.writeFile(listType(), out, filters);
        String xml = new String(out.toByteArray(), StandardCharsets.UTF_8);

        String footer = xml.substring(xml.indexOf("<!-- Content filtered by -->"));
        assertEquals("<!-- Content filtered by -->\n"
                + "<!-- [ INSTRUMENT FILE ] A filter : no condition -->\n"
                + "<!-- [Instrument File] b filter : (SANCTIONED equals Yes) AND (TARGET equals EU OR TARGET equals UK OR X  ) -->\n"
                + "<!-- [Instrument File] b filter : rows of the CMIC rule excluded -->\n"
                + "<!-- [Instrument File] b filter : rows of the E014071 rule excluded -->\n"
                + "<!-- [Structured File] s- -name : (N contains a b) -->\n"
                + "<!-- [Options File] opt : (A equals x) -->\n"
                + "<!-- [something else]  : (F is empty ) -->\n"
                + "<!-- [] z : no condition -->\n"
                + "<!-- ######################################################################## -->\n", footer);
        assertTrue(xml.contains("<label>UK</label>"));
    }

    @Test
    public void sixFooter_noFilters_or_notAvailable() {
        assertTrue(ExportFileWriter.footer(Collections.emptyList()).contains("<!-- no active filter -->"));
        assertTrue(ExportFileWriter.footer(null).contains("<!-- filters not available -->"));
    }

    @Test
    public void sixFooter_conditionsError_doesNotStopTheFile() {
        SixFilters broken = filter("Instrument File", "broken", false, false);
        when(broken.getActiveSixSubfilters()).thenThrow(new IllegalStateException("lazy init"));
        assertTrue(ExportFileWriter.footer(Collections.singletonList(broken))
                .contains("<!-- [Instrument File] broken : conditions not available -->"));
    }

    @Test
    public void writeFile_six_nullObject_refused() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<SixFilters> none = Collections.emptyList();
        try {
            ExportFileWriter.writeFile(null, out, none);
            fail();
        } catch (IllegalArgumentException e) {
            assertEquals(0, out.size());
        }
    }

    // ------------------------------------------------------------------ JAXB context

    @Test
    public void jaxbContext_createdOnce() throws Exception {
        ExportFileWriter.writeFile(listType(), new ByteArrayOutputStream(), null);
        JAXBContext first = context().get();
        ExportFileWriter.writeFile(listType(), new ByteArrayOutputStream(), null);
        assertSame(first, context().get());
    }
}
