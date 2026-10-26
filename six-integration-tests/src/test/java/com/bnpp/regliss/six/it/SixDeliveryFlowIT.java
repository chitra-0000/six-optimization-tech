package com.bnpp.regliss.six.it;

import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.bnpp.regliss.six.it.SixItFiles.fileName;
import static com.bnpp.regliss.six.it.SixItFiles.instrument;
import static com.bnpp.regliss.six.it.SixItFiles.instrumentFile;
import static com.bnpp.regliss.six.it.SixItFiles.structured;
import static com.bnpp.regliss.six.it.SixItFiles.structuredFile;
import static com.bnpp.regliss.six.it.SixItFiles.target;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// TODO: re-add project imports (Alt+Enter / Optimize Imports) for: ReglissList, ImportFileType, SixBatchConfidencePoller

/**
 * SIX delivery (all or nothing): the confidence step starts only when every file of the delivery is imported, the
 * STRUCT file gets the confidence level of the INSTR file, then the files end at 100% and the filtered export (part 3)
 * is requested once per file.
 */
public class SixDeliveryFlowIT extends AbstractSixFlowIT {

    @Autowired
    private SixBatchConfidencePoller sixBatchConfidencePoller;

    private ReglissList instruments;
    private ReglissList structuredProducts;

    private void rawLists() {
        instruments = rawList(ImportFileType.SIX_INSTRUMENTS_FILE, "R01");
        structuredProducts = rawList(ImportFileType.SIX_STRUCTURED_FILE, "R02");
    }

    private ImportedSix importInstruments(String delivery) {
        return deliverAndImport(instruments, fileName("INSTR", delivery), instrumentFile(
                instrument("1001", "XS0000000001", "85", target("Target A", "EU", "Reg 833/2014")),
                instrument("1002", "XS0000000002", "40", target("Target B", "US", "EO 14024"))));
    }

    private ImportedSix importStructured(String delivery) {
        return deliverAndImport(structuredProducts, fileName("STRUCT", delivery), structuredFile(
                structured("2001", "CH0000000001", "1001", target("Target A", "EU", "Reg 833/2014")),
                structured("2002", "CH0000000002", "1002", target("Target B", "US", "EO 14024")),
                structured("2003", "CH0000000003", "9999", target("Target C", "UK", "SAMLA"))));
    }

    @Test
    public void completeDelivery_confidenceMerged_filesAt100_filteredExportRequested() {
        rawLists();
        outputList(OUTPUT_LIST);
        ImportedSix instr = importInstruments(DELIVERY);
        ImportedSix struct = importStructured(DELIVERY);

        sixBatchConfidencePoller.generateExportFiles();

        assertEquals("85", confidenceOfStructured(struct.versionId, "1001"));
        assertEquals("40", confidenceOfStructured(struct.versionId, "1002"));
        assertNull(confidenceOfStructured(struct.versionId, "9999"));           // underlying not in the INSTR file
        assertTrue(exportRequests("SIX_CONFIDENCE_VALUES").isEmpty());
        assertEquals(Arrays.asList(OUTPUT_LIST, OUTPUT_LIST), exportRequests("SIX_FILTERED_FILE_GENERATION"));
        assertTrue(jobFinished(instr.jobId));
        assertTrue(jobFinished(struct.jobId));
        assertEquals(Collections.emptyList(), fileNames(sixIn()));                      // delivery files removed from IN
    }

    @Test
    public void noSixOutputList_filesStillEndAt100_noExportRequested() {
        rawLists();
        ImportedSix instr = importInstruments(DELIVERY);
        ImportedSix struct = importStructured(DELIVERY);

        sixBatchConfidencePoller.generateExportFiles();

        assertEquals("85", confidenceOfStructured(struct.versionId, "1001"));
        assertTrue(exportRequests("SIX_CONFIDENCE_VALUES").isEmpty());
        assertTrue(exportRequests("SIX_FILTERED_FILE_GENERATION").isEmpty());
        assertTrue(jobFinished(instr.jobId));
        assertTrue(jobFinished(struct.jobId));
        assertEquals(Collections.emptyList(), fileNames(sixIn()));
    }

    @Test
    public void otherFileOfTheDeliveryNotImportedYet_waitsAt90() {
        rawLists();
        ImportedSix instr = importInstruments(DELIVERY);
        deliverToSixIn(fileName("STRUCT", DELIVERY), structuredFile(
                structured("2001", "CH0000000001", "1001", target("Target A", "EU", "Reg 833/2014"))));

        sixBatchConfidencePoller.generateExportFiles();

        assertEquals(1, exportRequests("SIX_CONFIDENCE_VALUES").size());               // still waiting
        assertTrue(jobOpen(instr.jobId));
        assertEquals(2, fileNames(sixIn()).size());
    }

    @Test
    public void fileOfTheDeliveryRemovedFromIn_wholeDeliveryRolledBack() throws Exception {
        rawLists();
        ImportedSix instr = importInstruments(DELIVERY);
        ImportedSix struct = importStructured(DELIVERY);
        Files.delete(sixIn().resolve(struct.fileName));                                   // e.g. moved out by an operator

        sixBatchConfidencePoller.generateExportFiles();

        assertEquals(0, count("SELECT COUNT(*) FROM SIX_INSTRUMENTS WHERE VERSION_ID = ?", instr.versionId));
        assertEquals(0, count("SELECT COUNT(*) FROM SIX_STRUCTURED WHERE VERSION_ID = ?", struct.versionId));
        assertTrue(exportRequests("SIX_CONFIDENCE_VALUES").isEmpty());
        assertTrue(exportRequests("SIX_FILTERED_FILE_GENERATION").isEmpty());
        assertFalse(jobOpen(instr.jobId));
        assertFalse(jobOpen(struct.jobId));
        assertEquals(Collections.singletonList(instr.fileName), fileNames(sixError()));   // the rest of the delivery -> ERROR
    }

    private String confidenceOfStructured(long versionId, String underlyingCh) {
        List<String> values = jdbc.queryForList("SELECT DISTINCT CONFIDENCE_LEVEL FROM SIX_STRUCTURED WHERE VERSION_ID = ?"
                + " AND UNDERLYING_CH = ?", String.class, versionId, underlyingCh);
        assertEquals(1, values.size());
        return values.get(0);
    }
}
