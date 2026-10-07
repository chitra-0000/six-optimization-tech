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
     */


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
            // -----------------------------------------------------------------
            // ① Set up JAXB (identical to your original code)
            // -----------------------------------------------------------------
            JAXBContext ctx = JAXBContext.newInstance(ListType.class);
            Marshaller marshaller = ctx.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
            marshaller.setProperty(Marshaller.JAXB_ENCODING, StandardCharsets.UTF_8.name());

            // -----------------------------------------------------------------
            // ② Marshal to a ByteArrayOutputStream
            // -----------------------------------------------------------------
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            marshaller.marshal(xmlObj, baos);

            // -----------------------------------------------------------------
            // ③ Build the footer comment block (Java 8 style - no text blocks)
            // -----------------------------------------------------------------
            String now = LocalDateTime.now(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

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

            // -----------------------------------------------------------------
            // ④ Append the footer (same UTF-8 charset)
            // -----------------------------------------------------------------
            baos.write(footerBuilder.toString().getBytes(StandardCharsets.UTF_8));

            // -----------------------------------------------------------------
            // ⑤ Return a single InputStream that the caller can read from
            // -----------------------------------------------------------------
            return new ByteArrayInputStream(baos.toByteArray());

        } catch (JAXBException e) {
            // keep the original behaviour
            throw new RuntimeException("Failed to marshal ListType to XML", e);
        } catch (IOException e) {
            // ByteArrayOutputStream never really throws IOException, but the compiler
            // forces us to catch it because we called baos.write(byte[])
            throw new UncheckedIOException("Unable to append footer", e);
        }
    }

    /** Helper that converts {@code null} into an empty string - avoids NPEs in StringBuilder. */
    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

}
