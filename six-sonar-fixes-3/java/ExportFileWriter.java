package com.bnpp.regliss.six.exporter.service;

import lombok.extern.slf4j.Slf4j;

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
import java.util.concurrent.atomic.AtomicReference;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

// TODO: re-add project imports for: ListType, SixFilters, SixSubFilters, SixSubORFilters

@Slf4j
public final class ExportFileWriter {
    /**
     * Utility that marshals a {@code ListType} instance to XML and
     * appends a comment footer (timestamp, filter info, ref-code list).
     *
     * Export phase 1 (memory): {@link #writeFile} writes the same bytes straight to a stream (the SIX export writes
     * to the file), instead of keeping the whole XML twice in memory (ByteArrayOutputStream + toByteArray copy).
     * The JAXBContext (thread safe, expensive to create) is created once instead of once per file.
     * {@link #createFile} is unchanged for its callers. Static helpers only (no instance).
     */

    private static final DateTimeFormatter FOOTER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String FOOTER_LINE = "<!-- ######################################################################## -->\n";
    /** Order of the filter lines in the footer (file types in lower case). */
    private static final List<String> FILE_TYPE_ORDER = Arrays.asList("instrument file", "structured file", "options file",
            "data extractor 1", "data extractor 2");
    private static final Comparator<SixFilters> FILTER_ORDER = Comparator.comparingInt((SixFilters f) -> fileTypeRank(f.getFileType()))
            .thenComparing(f -> nullToEmpty(f.getFilterName()), String.CASE_INSENSITIVE_ORDER);

    private static final String XML_OBJ_REQUIRED = "xmlObj must not be null";

    /**
     * JAXBContext of ListType, created on first use and then shared (JAXBContext is thread safe; the Marshaller is not,
     * one per call). AtomicReference: safe publication without double-checked locking.
     */
    private static final AtomicReference<JAXBContext> LIST_TYPE_CONTEXT = new AtomicReference<>();

    private ExportFileWriter() {
        // static helpers only
    }

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
            throw new IllegalArgumentException(XML_OBJ_REQUIRED);
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
            throw new IllegalArgumentException(XML_OBJ_REQUIRED);
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
        footerBuilder.append(FOOTER_LINE);
        footerBuilder.append("<!--     ").append(now).append("  -->\n");
        footerBuilder.append("<!--     Content filter by [")
                .append(nullToEmpty(filterUser)).append("] on [")
                .append(nullToEmpty(filterSource)).append("]  -->\n");
        footerBuilder.append("<!--     with [")
                .append(nullToEmpty(filterProgram)).append("] - [")
                .append(nullToEmpty(filterVersion)).append("]  -->\n");
        footerBuilder.append("<!--          Refcode : ")
                .append(nullToEmpty(refcodeList)).append("  -->\n");
        footerBuilder.append(FOOTER_LINE);
        return footerBuilder.toString();
    }

    /**
     * SIX CONVERTER file: same XML, then the footer describing the filters of the list ({@link #footer(List)}).
     *
     * @param filters active filters of the list (null: they could not be read - the footer says so)
     */
    public static void writeFile(ListType xmlObj, OutputStream out, List<SixFilters> filters) throws JAXBException, IOException {
        if (xmlObj == null) {
            throw new IllegalArgumentException(XML_OBJ_REQUIRED);
        }
        Marshaller marshaller = listTypeContext().createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
        marshaller.setProperty(Marshaller.JAXB_ENCODING, StandardCharsets.UTF_8.name());
        marshaller.marshal(xmlObj, out);
        out.write(footer(filters).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Footer of a SIX CONVERTER file:
     * <pre>
     * &lt;!-- ##### --&gt;
     * &lt;!-- 2026-10-19 17:05:33 --&gt;
     * &lt;!-- Content filtered by --&gt;
     * &lt;!-- [Instrument File] UK filter : (SANCTIONED equals Yes) AND (UK - REGIME is not empty true) --&gt;
     * &lt;!-- [Instrument File] UK filter : rows of the CMIC rule excluded --&gt;
     * &lt;!-- ##### --&gt;
     * </pre>
     * One line per active filter (file type, name, its sub-filters joined with AND, the rows of a sub-filter with OR),
     * in the order Instrument, Structured, Options, Data extractor 1 / 2; then one line per CMIC / E014071 exclusion
     * of that filter.
     */
    static String footer(List<SixFilters> filters) {
        StringBuilder footer = new StringBuilder(FOOTER_LINE);
        comment(footer, LocalDateTime.now(ZoneOffset.UTC).format(FOOTER_TIME));
        comment(footer, "Content filtered by");
        if (filters == null) {
            comment(footer, "filters not available");
        } else if (filters.isEmpty()) {
            comment(footer, "no active filter");
        } else {
            filters.stream().filter(Objects::nonNull).sorted(FILTER_ORDER).forEach(filter -> {
                String prefix = "[" + nullToEmpty(filter.getFileType()) + "] " + nullToEmpty(filter.getFilterName()) + " : ";
                comment(footer, prefix + conditions(filter));
                if (filter.isExcludeCMIC()) {
                    comment(footer, prefix + "rows of the CMIC rule excluded");
                }
                if (filter.isExcludeE014071()) {
                    comment(footer, prefix + "rows of the E014071 rule excluded");
                }
            });
        }
        footer.append(FOOTER_LINE);
        return footer.toString();
    }

    /** Sub-filters joined with AND, the rows of one sub-filter with OR (the footer never stops the file). */
    private static String conditions(SixFilters filter) {
        try {
            return describe(filter);
        } catch (RuntimeException e) {
            log.warn("SIX footer: conditions of filter {} not available: {}", filter.getFilterName(), e.getMessage());
            return "conditions not available";
        }
    }

    private static String describe(SixFilters filter) {
        String conditions = filter.getActiveSixSubfilters().stream()
                .sorted(Comparator.comparing(SixSubFilters::getSixIndex, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(sub -> sub.getActiveSixSubORfilters().stream()
                        .sorted(Comparator.comparing(SixSubORFilters::getSixORIndex, Comparator.nullsLast(Comparator.naturalOrder())))
                        .map(or -> nullToEmpty(or.getFieldName()) + " " + nullToEmpty(or.getOperator()) + " " + nullToEmpty(or.getFilterValue()))
                        .collect(Collectors.joining(" OR ", "(", ")")))
                .filter(group -> !"()".equals(group))
                .collect(Collectors.joining(" AND "));
        return conditions.isEmpty() ? "no condition" : conditions;
    }

    /** One comment line; the text can never close the comment ("--" is not allowed inside an XML comment). */
    private static void comment(StringBuilder footer, String text) {
        String safe = text.replaceAll("[\\r\\n\\t]+", " ");
        while (safe.contains("--")) {
            safe = safe.replace("--", "- -");
        }
        footer.append("<!-- ").append(safe).append(" -->\n");
    }

    private static int fileTypeRank(String fileType) {
        int rank = FILE_TYPE_ORDER.indexOf(fileType == null ? "" : fileType.trim().toLowerCase(Locale.ROOT));
        return rank < 0 ? FILE_TYPE_ORDER.size() : rank;
    }

    private static JAXBContext listTypeContext() throws JAXBException {
        JAXBContext context = LIST_TYPE_CONTEXT.get();
        if (context == null) {
            // created at most a few times if two threads start together; the first one stored is kept for all
            LIST_TYPE_CONTEXT.compareAndSet(null, JAXBContext.newInstance(ListType.class));
            context = LIST_TYPE_CONTEXT.get();
        }
        return context;
    }

    /** Helper that converts {@code null} into an empty string - avoids NPEs in StringBuilder. */
    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

}
