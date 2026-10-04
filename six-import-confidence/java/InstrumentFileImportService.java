package com.bnpp.regliss.importer.six.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.xml.stream.XMLStreamException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

// TODO: re-add project imports for: ReglissBatchProfile, Version, ReglissList, InstrumentFile, InstrumentFilesDto, InstrumentFileMapper

/**
 * Reads the XSLT output record by record and writes it with JDBC batch INSERTs
 * (parents + SIX_TARGET children together). No class-level @Transactional: each
 * batch commits on its own inside SixJdbcBulkWriter.
 */
@Service
@ReglissBatchProfile
@Slf4j
public class InstrumentFileImportService {

    /** Record element written by instruments-xslt.xml. */
    static final String RECORD_ELEMENT = "INSTRUMENT_FILE";

    @Autowired
    private InstrumentFileMapper instrumentFileMapper;

    @Autowired
    private SixBatchImportRunner runner;

    @Autowired
    private SixJdbcBulkWriter writer;

    public void streamAndPersist(File transformedXml, Version version, ReglissList list, long batchExecutionId)
            throws IOException, XMLStreamException {
        runner.run(new SixBatchImportRunner.RecordType<>("instrument", RECORD_ELEMENT, InstrumentFilesDto.class,
                        instrumentFileMapper::mapperStructuredFile,
                        (batch, v, l) -> writer.insertBatch(batch, l.getId(), v.getId(), InstrumentFile::getSixTargets, "SIX_INSTRU_ID")),
                transformedXml, version, list, batchExecutionId);
    }

    /** Kept for any other caller that still passes the XML as a String. */
    public void streamAndPersist(String transformedXml, Version version, ReglissList list, long batchExecutionId)
            throws IOException, XMLStreamException {
        File tmp = Files.createTempFile("six-instrument-", ".xml").toFile();   // random name, owner-only permissions on Linux
        try {
            Files.write(tmp.toPath(), transformedXml.getBytes(StandardCharsets.UTF_8));
            streamAndPersist(tmp, version, list, batchExecutionId);
        } finally {
            Files.deleteIfExists(tmp.toPath());
        }
    }
}
