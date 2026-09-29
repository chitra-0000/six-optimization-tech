package com.bnpp.regliss.importer.six.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import javax.xml.XMLConstants;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import java.util.stream.Collectors;

// TODO: re-add project imports (Alt+Enter / Optimize Imports) for:
// ReglissBatchProfile, CloseResourcesAfter, VersionRepository, Version, ReglissList, ReglissException,
// ImportFileType, PerformanceMonitor, UnicodeBOMInputStream

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

    @CloseResourcesAfter
    public void importAutomaticFileWithoutTx(Supplier<UnicodeBOMInputStream> xmlInputStreamSupplier,
                                             ReglissList list, long versionId, long batchExecutionId) {
        try {
            PerformanceMonitor.before(PerformanceMonitor.Action.EVERYTHING);

            StreamSource xlstInputStream = createStreamSource(list);
            String xstl = convertIntoXslt(xlstInputStream);
            String transformed = convertTransformedXml(xmlInputStreamSupplier, xstl);

            Version version = versionRepository.findById(versionId).orElseThrow(() -> new ReglissException(ReglissException.ErrorCode.NOT_FOUND));
            if (list.getImportFileType().isSIXStructuredFileType()) {
                structuredFileImportService.streamAndPersist(transformed, version, list, batchExecutionId);
            } else if (list.getImportFileType().isSIXINSTRUMENTSFileType()) {
                instrumentFileImportService.streamAndPersist(transformed, version, list, batchExecutionId);
            } else if (list.getImportFileType().isSIXOptionsFileType()) {
                optionsFileImportService.streamAndPersist(transformed, version, list, batchExecutionId);
            }

            PerformanceMonitor.after(PerformanceMonitor.Action.EVERYTHING);
        } catch (Exception e) {
            throw new ReglissException("Six raw import failed");
        } finally {
            PerformanceMonitor.printStats();
            PerformanceMonitor.clearStats();
        }

    }

    private String convertTransformedXml(Supplier<UnicodeBOMInputStream> xmlInputStreamSupplier, String xstl) {
        try {
            return transform(xmlInputStreamSupplier, xstl);
        } catch (IOException | TransformerException e) {
            e.printStackTrace();
        }
        return null;
    }

    private String convertIntoXslt(StreamSource xlstInputStream) {
        try (InputStream in = xlstInputStream.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

            return reader.lines()
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private StreamSource createStreamSource(ReglissList list) {
        StreamSource xlstInputStream = new StreamSource();
        try {
            xlstInputStream = new StreamSource(getTransformerFileByType(list.getImportConfiguration().getImportFileType()).getInputStream());
        } catch (IOException e) {
            e.printStackTrace();
        }
        return xlstInputStream;
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
                throw new IllegalArgumentException();
        }
    }

    private String transform(Supplier<UnicodeBOMInputStream> xmlSupplier, String xsltContent) throws IOException, TransformerException {
        TransformerFactory tf = TransformerFactory.newInstance();
        tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        StreamSource xsltSource = new StreamSource(new StringReader(xsltContent));
        Transformer transformer = tf.newTransformer(xsltSource);

        try (UnicodeBOMInputStream bomIn = xmlSupplier.get()) {
            StreamSource xmlSource = new StreamSource(bomIn);
            StringWriter out = new StringWriter();
            StreamResult result = new StreamResult(out);
            transformer.transform(xmlSource, result);
            return out.toString();
        }
    }
}
