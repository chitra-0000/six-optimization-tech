package com.bnpp.regliss.six.it;

import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static com.bnpp.regliss.six.it.SixItFiles.fileName;
import static com.bnpp.regliss.six.it.SixItFiles.instrument;
import static com.bnpp.regliss.six.it.SixItFiles.instrumentFile;
import static com.bnpp.regliss.six.it.SixItFiles.structured;
import static com.bnpp.regliss.six.it.SixItFiles.structuredFile;
import static com.bnpp.regliss.six.it.SixItFiles.target;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// TODO: re-add project imports (Alt+Enter / Optimize Imports) for: ReglissList, ImportFileType, SixBatchConfidencePoller,
// BatchExportPoller, SixXmlGenerationPoller

/**
 * SIX filtered export (part 3) after a finished delivery: filtering into FILTERED_SIX_*, one CONVERTER file per output
 * list built in the holding folder, released into the DJ IN folder once every file type of the delivery is built.
 */
public class SixExportFlowIT extends AbstractSixFlowIT {

    @Autowired
    private SixBatchConfidencePoller sixBatchConfidencePoller;
    @Autowired
    private BatchExportPoller batchExportPoller;
    @Autowired
    private SixXmlGenerationPoller sixXmlGenerationPoller;

    @Before
    public void finishedDelivery() {
        ReglissList instruments = rawList(ImportFileType.SIX_INSTRUMENTS_FILE, "R01");
        ReglissList structuredProducts = rawList(ImportFileType.SIX_STRUCTURED_FILE, "R02");
        outputList(OUTPUT_LIST);
        deliverAndImport(instruments, fileName("INSTR", DELIVERY), instrumentFile(
                instrument("1001", "XS0000000001", "85", target("Target A", "EU", "Reg 833/2014")),
                instrument("1002", "XS0000000002", "40", target("Target B", "US", "EO 14024"))));
        deliverAndImport(structuredProducts, fileName("STRUCT", DELIVERY), structuredFile(
                structured("2001", "CH0000000001", "1001", target("Target A", "EU", "Reg 833/2014"))));
        sixBatchConfidencePoller.generateExportFiles();
        assertEquals(2, exportRequests("SIX_FILTERED_FILE_GENERATION").size());
    }

    @Test
    public void filteredRowsWritten_thenConverterFileReleasedIntoDjIn() {
        batchExportPoller.generateExportFiles();                                          // filtering of INSTR and STRUCT

        assertTrue(exportRequests("SIX_FILTERED_FILE_GENERATION").isEmpty());
        assertEquals(2, count("SELECT COUNT(*) FROM FILTERED_SIX_INSTRUMENTS WHERE SIX_LIST_REF = ?", OUTPUT_LIST));
        assertEquals(1, count("SELECT COUNT(*) FROM FILTERED_SIX_STRUCTURED WHERE SIX_LIST_REF = ?", OUTPUT_LIST));
        assertEquals(2, count("SELECT COUNT(*) FROM SIX_FILTERED_POLLER WHERE SIX_LIST_REFERENCE = ? AND STATUS = 'READY'", OUTPUT_LIST));
        assertTrue(fileNames(djIn()).isEmpty());

        sixXmlGenerationPoller.generateXmlFiles();                                     // build + release

        List<String> delivered = fileNames(djIn());
        assertEquals(1, delivered.size());
        assertTrue(delivered.get(0), delivered.get(0).startsWith("CONVERTER-") && delivered.get(0).endsWith(".xml"));
        String xml = text(djIn().resolve(delivered.get(0)));
        assertTrue(xml.contains("XS0000000001"));
        assertTrue(xml.contains("XS0000000002"));
        assertTrue(xml.contains("CH0000000001"));
        assertTrue(xml, xml.contains("<!-- no active filter -->"));
        assertEquals(0, count("SELECT COUNT(*) FROM SIX_FILTERED_POLLER WHERE STATUS IN ('READY', 'BUILDING', 'BUILT')"));
    }

    @Test
    public void onlyOneFileTypeFiltered_noConverterFileYet() {
        List<Long> requests = jdbc.queryForList("SELECT ID FROM CTR_BATCH_EXPORT WHERE BATCH_TYPE = 'SIX_FILTERED_FILE_GENERATION'"
                + " ORDER BY ID", Long.class);
        jdbc.update("DELETE FROM CTR_BATCH_EXPORT WHERE ID = ?", requests.get(1));        // the STRUCT request is not run

        batchExportPoller.generateExportFiles();
        sixXmlGenerationPoller.generateXmlFiles();

        assertEquals(1, count("SELECT COUNT(*) FROM SIX_FILTERED_POLLER WHERE SIX_LIST_REFERENCE = ? AND STATUS = 'READY'", OUTPUT_LIST));
        assertTrue(fileNames(djIn()).isEmpty());                                         // waits for the other file type
    }
}
