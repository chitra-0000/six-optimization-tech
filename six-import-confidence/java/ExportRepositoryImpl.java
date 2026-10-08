package com.bnpp.regliss.six.export;

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
public class ExportRepositoryImpl {
    /**
     * Utility that marshals a {@code ListType} instance to XML and
     * appends a comment footer (timestamp, filter info, ref-code list).
     *
     * Export phase 1 (memory): {@link #writeFile} writes the same bytes straight to a stream (the SIX export writes
     * to the file), instead of keeping the whole XML twice in memory (ByteArrayOutputStream + toByteArray copy).
     * The JAXBContext (thread safe, expensive to create) is created once instead of once per file.
     * {@link #createFile} is unchanged for its callers.
     */

    private static final DateTimeFormatter FOOTER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** JAXBContext of ListType, created on first use (thread safe; the Marshaller is not, one per call). */
    private static volatile JAXBContext listTypeContext;

    /**
     * Marshals {@code xmlObj} and returns an {@link InputStream}
     * containing the XML **plus** the footer comment block.
     *
     * @param xmlObj        the populated JAXB root element (must not be {@code null})
     * @param filterUser    e.g. "462656"
     * @param filterSource  e.g. "PARM00312526"
     * @param filterProgram e.g. "APAC Consolidated List Filter"
     * @param filterVersion e.g. "v1.3"
     * @param refcodeList   a pipe-separated list (may be empty)
     * @return an {@link InputStream} with the complete payload
     * @throws JAXBException if marshalling fails
     */
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
            // keep the original behaviour
            throw new RuntimeException("Failed to marshal ListType to XML", e); // NOSONAR original contract of createFile
        } catch (IOException e) {
            // ByteArrayOutputStream never really throws IOException
            throw new UncheckedIOException("Unable to append footer", e);
        }
    }

    /**
     * Same content as {@link #createFile} (XML, then the footer comment block), written to {@code out}. The stream is
     * not closed.
     *
     * @throws JAXBException if marshalling fails
     * @throws IOException   if the stream cannot be written (disk full ...)
     */
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

    /** Footer comment block (Java 8 style - no text blocks), identical to the original. */
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
            synchronized (ExportRepositoryImpl.class) {
                context = listTypeContext;
                if (context == null) {
                    context = JAXBContext.newInstance(ListType.class);
                    listTypeContext = context;
                }
            }
        }
        return context;
    }

    /** Helper that converts {@code null} into an empty string - avoids NPEs in StringBuilder. */
    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

}
