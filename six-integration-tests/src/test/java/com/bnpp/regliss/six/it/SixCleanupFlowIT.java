package com.bnpp.regliss.six.it;

import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static com.bnpp.regliss.six.it.SixItFiles.fileName;
import static com.bnpp.regliss.six.it.SixItFiles.instrument;
import static com.bnpp.regliss.six.it.SixItFiles.instrumentFile;
import static com.bnpp.regliss.six.it.SixItFiles.structured;
import static com.bnpp.regliss.six.it.SixItFiles.structuredFile;
import static com.bnpp.regliss.six.it.SixItFiles.target;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// TODO: re-add project imports (Alt+Enter / Optimize Imports) for: ReglissList, ImportFileType, SixBatchConfidencePoller,
// BatchExportPoller, SixXmlGenerationPoller, SixCleanupService

/** SIX nightly cleanup (part 4): older raw versions and the filtered tables are removed only when no SIX job runs. */
public class SixCleanupFlowIT extends AbstractSixFlowIT {

    @Autowired
    private SixBatchConfidencePoller sixBatchConfidencePoller;
    @Autowired
    private BatchExportPoller batchExportPoller;
    @Autowired
    private SixXmlGenerationPoller sixXmlGenerationPoller;
    @Autowired
    private SixCleanupService sixCleanupService;

    private ReglissList instruments;
    private ReglissList structuredProducts;

    @Before
    public void rawLists() {
        instruments = rawList(ImportFileType.SIX_INSTRUMENTS_FILE, "R01");
        structuredProducts = rawList(ImportFileType.SIX_STRUCTURED_FILE, "R02");
    }

    private ImportedSix[] importDelivery(String delivery) {
        ImportedSix instr = deliverAndImport(instruments, fileName("INSTR", delivery), instrumentFile(
                instrument("1001", "XS0000000001", "85", target("Target A", "EU", "Reg 833/2014"))));
        ImportedSix struct = deliverAndImport(structuredProducts, fileName("STRUCT", delivery), structuredFile(
                structured("2001", "CH0000000001", "1001", target("Target A", "EU", "Reg 833/2014"))));
        return new ImportedSix[]{instr, struct};
    }

    @Test
    public void twoFinishedDeliveries_olderRawVersionsDeleted_latestKept() {
        ImportedSix[] first = importDelivery(DELIVERY);
        sixBatchConfidencePoller.generateExportFiles();
        ImportedSix[] latest = importDelivery(NEXT_DELIVERY);
        sixBatchConfidencePoller.generateExportFiles();

        SixCleanupService.Result result = sixCleanupService.runCleanup();

        assertNull(result.getImportSkippedBecause());
        assertEquals(0, count("SELECT COUNT(*) FROM SIX_INSTRUMENTS WHERE VERSION_ID = ?", first[0].versionId));
        assertEquals(0, count("SELECT COUNT(*) FROM SIX_STRUCTURED WHERE VERSION_ID = ?", first[1].versionId));
        assertEquals(1, count("SELECT COUNT(*) FROM SIX_INSTRUMENTS WHERE VERSION_ID = ?", latest[0].versionId));
        assertEquals(1, count("SELECT COUNT(*) FROM SIX_STRUCTURED WHERE VERSION_ID = ?", latest[1].versionId));
        assertEquals(2, count("SELECT COUNT(*) FROM SIX_TARGET"));                         // only the latest rows' targets
    }

    @Test
    public void deliveryWaitingForItsConfidenceStep_rawPartSkipped() {
        ImportedSix[] first = importDelivery(DELIVERY);
        sixBatchConfidencePoller.generateExportFiles();
        deliverAndImport(instruments, fileName("INSTR", NEXT_DELIVERY), instrumentFile(
                instrument("1001", "XS0000000001", "85", target("Target A", "EU", "Reg 833/2014"))));   // waits at 90%

        SixCleanupService.Result result = sixCleanupService.runCleanup();

        assertNotNull(result.getImportSkippedBecause());
        assertEquals(1, count("SELECT COUNT(*) FROM SIX_INSTRUMENTS WHERE VERSION_ID = ?", first[0].versionId));
    }

    @Test
    public void exportFinished_filteredTablesCleared() {
        outputList(OUTPUT_LIST);
        importDelivery(DELIVERY);
        sixBatchConfidencePoller.generateExportFiles();
        batchExportPoller.generateExportFiles();
        sixXmlGenerationPoller.generateXmlFiles();
        assertEquals(1, fileNames(djIn()).size());
        assertTrue(count("SELECT COUNT(*) FROM FILTERED_SIX_INSTRUMENTS") > 0);

        SixCleanupService.Result result = sixCleanupService.runCleanup();

        assertNull(result.getExportSkippedBecause());
        assertTrue(result.isExportTablesCleared());
        assertEquals(0, count("SELECT COUNT(*) FROM FILTERED_SIX_INSTRUMENTS"));
        assertEquals(0, count("SELECT COUNT(*) FROM FILTERED_SIX_STRUCTURED"));
        assertEquals(0, count("SELECT COUNT(*) FROM FILTERED_SIX_TARGET"));
        assertEquals(1, fileNames(djIn()).size());                                        // the delivered file stays
    }
}
