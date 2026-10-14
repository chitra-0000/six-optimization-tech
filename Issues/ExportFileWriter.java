package com.bnpp.regliss.six.export.service;

// TODO: imports (lines 3-15) were folded in the IDE; reconstructed from usage. Add the ListType import (package not visible).
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.xml.bind.JAXBContext;          // jakarta.xml.bind.* on Spring Boot 3
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@AllArgsConstructor
@Slf4j
@Service
public class ExportFileWriter {

    private static final DateTimeFormatter FOOTER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static volatile JAXBContext listTypeContext;

    public static InputStream createFile(
            ListType xmlObj,
            String filterUser,
            String filterSource,
            String filterProgram,
            String filterVersion,
            String refcodeList) throws JAXBException {

        if (xmlObj == null) {
            throw new IllegalArgumentException("xmlObj must not be null");
        }

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            writeFile(xmlObj, baos, filterUser, filterSource, filterProgram, filterVersion, refcodeList);
            return new ByteArrayInputStream(baos.toByteArray());

        } catch (JAXBException e) {
            throw new RuntimeException("Failed to marshal ListType to XML", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to append footer", e);
        }
    }

    public static void writeFile(
            ListType xmlObj,
            OutputStream out,
            String filterUser,
            String filterSource,
            String filterProgram,
            String filterVersion,
            String refcodeList) throws JAXBException, IOException {

        if (xmlObj == null) {
            throw new IllegalArgumentException("xmlObj must not be null");
        }
        Marshaller marshaller = listTypeContext().createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
        marshaller.setProperty(Marshaller.JAXB_ENCODING, StandardCharsets.UTF_8.name());
        marshaller.marshal(xmlObj, out);
        out.write(footer(filterUser, filterSource, filterProgram, filterVersion, refcodeList).getBytes(StandardCharsets.UTF_8));
    }

    private static String footer(String filterUser, String filterSource, String filterProgram, String filterVersion,
                                 String refcodeList) {
        String now = LocalDateTime.now(ZoneOffset.UTC).format(FOOTER_TIME);

        StringBuilder footerBuilder = new StringBuilder();
        footerBuilder.append("<!-- ######################################################################## -->\n");
        footerBuilder.append("<!--     ").append(now).append("  -->\n");
        footerBuilder.append("<!--     Content filter by [")
                .append(nullToEmpty(filterUser)).append("] on [")
                .append(nullToEmpty(filterSource)).append("]  -->\n");
        footerBuilder.append("<!--     with [")
                .append(nullToEmpty(filterProgram)).append("] - [")
                .append(nullToEmpty(filterVersion)).append("]  -->\n");
        footerBuilder.append("<!--          Refcode : ")
                .append(nullToEmpty(refcodeList)).append("  -->\n");
        footerBuilder.append("<!-- ######################################################################## -->\n");
        return footerBuilder.toString();
    }

    private static JAXBContext listTypeContext() throws JAXBException {
        JAXBContext context = listTypeContext;
        if (context == null) {
            synchronized (ExportFileWriter.class) {
                context = listTypeContext;
                if (context == null) {
                    context = JAXBContext.newInstance(ListType.class);
                    listTypeContext = context;
                }
            }
        }
        return context;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

}
