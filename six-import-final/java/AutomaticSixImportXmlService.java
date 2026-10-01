package com.bnpp.regliss.importer.six.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import javax.xml.XMLConstants;
import javax.xml.transform.Templates;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

// TODO: re-add project imports for:
// ReglissBatchProfile, CloseResourcesAfter, VersionRepository, Version, ReglissList, ReglissException,
// ImportFileType, PerformanceMonitor, UnicodeBOMInputStream, InstrumentFile, StructuredFile, OptionsFile

/**
 * Entry point of the SIX raw import (called by AutomaticImporter, signature unchanged).
 *
 * Changes:
 *  1. XSLT output goes to a temp FILE instead of a StringWriter. The old code held the
 *     whole transformed document as one String (2 bytes per char), then Jackson built one
 *     List with every record from it: several GB of heap per file, heavy GC.
 *  2. The compiled XSLT (Templates) is cached and reused. Templates is thread-safe; the old
 *     code re-read and re-compiled the stylesheet on every import.
 *  3. Errors are no longer swallowed. convertTransformedXml() used to printStackTrace() and
 *     return null, and the final catch threw "Six raw import failed" WITHOUT the cause.
 *  4. If the import fails, the rows already written for this version are deleted, so a
 *     failed run never leaves a half-loaded version for the next steps to pick up.
 *  5. Timing per stage in the log (XSLT, DB write, total) to measure the next bottleneck.
 */
@Service
@ReglissBatchProfile
@Slf4j
public class AutomaticSixImportXmlService {

    @Value("${automatic.import.transformer.SIX_INSTRUMENT}")
    private Resource transformerFileSixInstrumentAutomatic;

    @Value("${automatic.import.transformer.SIX_STRUCTURE}")
    private Resource transformerFileSixStructureAutomatic;

    @Value("${automatic.import.transformer.SIX_OPTION}")
    private Resource transformerFileSixOptionAutomatic;

    @Autowired
    private VersionRepository versionRepository;

    @Autowired
    private StructuredFileImportService structuredFileImportService;

    @Autowired
    private InstrumentFileImportService instrumentFileImportService;

    @Autowired
    private OptionsFileImportService optionsFileImportService;

    @Autowired
    private SixJdbcBulkWriter bulkWriter;

    private final Map<ImportFileType, Templates> templatesCache = new ConcurrentHashMap<>();

    @CloseResourcesAfter
    public void importAutomaticFileWithoutTx(Supplier<UnicodeBOMInputStream> xmlInputStreamSupplier,
                                             ReglissList list, long versionId, long batchExecutionId) {
        ImportFileType fileType = list.getImportConfiguration().getImportFileType();
        long start = System.currentTimeMillis();
        File transformed = null;
        boolean writing = false;
        try {
            PerformanceMonitor.before(PerformanceMonitor.Action.EVERYTHING);

            transformed = transformToTempFile(xmlInputStreamSupplier, fileType);
            log.info("SIX {} XSLT done in {}s ({} MB)", fileType,
                    (System.currentTimeMillis() - start) / 1000, transformed.length() / (1024 * 1024));

            Version version = versionRepository.findById(versionId)
                    .orElseThrow(() -> new ReglissException(ReglissException.ErrorCode.NOT_FOUND));

            writing = true;
            if (list.getImportFileType().isSIXStructuredFileType()) {
                structuredFileImportService.streamAndPersist(transformed, version, list, batchExecutionId);
            } else if (list.getImportFileType().isSIXINSTRUMENTSFileType()) {
                instrumentFileImportService.streamAndPersist(transformed, version, list, batchExecutionId);
            } else if (list.getImportFileType().isSIXOptionsFileType()) {
                optionsFileImportService.streamAndPersist(transformed, version, list, batchExecutionId);
            }

            PerformanceMonitor.after(PerformanceMonitor.Action.EVERYTHING);
            log.info("SIX {} import finished in {}s", fileType, (System.currentTimeMillis() - start) / 1000);
        } catch (Exception e) {
            log.error("SIX {} raw import failed for version {}", fileType, versionId, e);
            if (writing) {
                removePartialVersion(list, versionId);
            }
            if (e instanceof ReglissException) {
                throw (ReglissException) e;
            }
            throw new ReglissException("Six raw import failed: " + e.getMessage());   // cause kept in the log above
        } finally {
            PerformanceMonitor.printStats();
            PerformanceMonitor.clearStats();
            deleteQuietly(transformed);
        }
    }

    private void removePartialVersion(ReglissList list, long versionId) {
        try {
            Class<?> entity = list.getImportFileType().isSIXStructuredFileType() ? StructuredFile.class
                    : list.getImportFileType().isSIXINSTRUMENTSFileType() ? InstrumentFile.class
                    : OptionsFile.class;
            int removed = bulkWriter.deleteVersion(entity, versionId);
            log.warn("Removed {} partially imported rows of version {}", removed, versionId);
        } catch (Exception cleanupError) {
            log.error("Could not remove partial rows of version {} - clean up manually", versionId, cleanupError);
        }
    }

    private File transformToTempFile(Supplier<UnicodeBOMInputStream> xmlSupplier, ImportFileType fileType)
            throws IOException, TransformerException {
        // java.io.tmpdir = /applis/11672-regli/tmp on the servers (JVM argument).
        File out = File.createTempFile("six-" + fileType.name().toLowerCase() + "-", ".xml");
        try (UnicodeBOMInputStream in = xmlSupplier.get();
             OutputStream os = new BufferedOutputStream(Files.newOutputStream(out.toPath()), 1 << 16)) {
            templatesFor(fileType).newTransformer().transform(new StreamSource(in), new StreamResult(os));
        } catch (IOException | TransformerException | RuntimeException e) {
            deleteQuietly(out);
            throw e;
        }
        return out;
    }

    private Templates templatesFor(ImportFileType fileType) {
        return templatesCache.computeIfAbsent(fileType, type -> {
            try (InputStream xslt = getTransformerFileByType(type).getInputStream()) {
                TransformerFactory tf = TransformerFactory.newInstance();
                tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
                tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                return tf.newTemplates(new StreamSource(xslt));
            } catch (IOException | TransformerException e) {
                throw new IllegalStateException("Cannot compile XSLT for " + type, e);
            }
        });
    }

    private Resource getTransformerFileByType(ImportFileType fileType) {
        switch (fileType) {
            case SIX_INSTRUMENTS_FILE:
                return transformerFileSixInstrumentAutomatic;
            case SIX_STRUCTURED_FILE:
                return transformerFileSixStructureAutomatic;
            case SIX_OPTIONS_FILE:
                return transformerFileSixOptionAutomatic;
            default:
                throw new IllegalArgumentException("Not a SIX file type: " + fileType);
        }
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.exists() && !f.delete()) {
            f.deleteOnExit();
        }
    }
}
