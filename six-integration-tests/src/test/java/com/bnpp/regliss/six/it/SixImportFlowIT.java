package com.bnpp.regliss.six.it;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static com.bnpp.regliss.six.it.SixItFiles.fileName;
import static com.bnpp.regliss.six.it.SixItFiles.instrument;
import static com.bnpp.regliss.six.it.SixItFiles.instrumentFile;
import static com.bnpp.regliss.six.it.SixItFiles.target;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

// TODO: re-add project imports (Alt+Enter / Optimize Imports) for: ReglissList, ImportFileType

/** SIX raw import: XSLT of the real file + parallel bulk write into SIX_INSTRUMENTS / SIX_TARGET. */
public class SixImportFlowIT extends AbstractSixFlowIT {

    @Test
    public void instrumentFile_oneRowPerTarget_withItsRegimes() {
        ReglissList instruments = rawList(ImportFileType.SIX_INSTRUMENTS_FILE, "R01");
        String xml = instrumentFile(
                instrument("1001", "XS0000000001", "85",
                        target("Target A", "EU", "Reg 833/2014").regime("UK", "SAMLA"),
                        target("Target B", "US", "EO 14024")),
                instrument("1002", "XS0000000002", "40", target("Target C", "EU", "Reg 269/2014")));

        ImportedSix imported = deliverAndImport(instruments, fileName("INSTR", DELIVERY), xml);

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT ID, ISIN, CH_VALOR, CONFIDENCE_LEVEL, LIST_ID FROM SIX_INSTRUMENTS"
                + " WHERE VERSION_ID = ? ORDER BY ISIN, ID", imported.versionId);
        assertEquals(3, rows.size());                                                   // one row per target
        assertEquals("XS0000000001", rows.get(0).get("ISIN"));
        assertEquals("1001", rows.get(0).get("CH_VALOR"));
        assertEquals("85", String.valueOf(rows.get(0).get("CONFIDENCE_LEVEL")));
        assertEquals(instruments.getId().longValue(), ((Number) rows.get(0).get("LIST_ID")).longValue());
        assertEquals("XS0000000002", rows.get(2).get("ISIN"));

        assertEquals(4, count("SELECT COUNT(*) FROM SIX_TARGET t JOIN SIX_INSTRUMENTS i ON i.ID = t.SIX_INSTRU_ID"
                + " WHERE i.VERSION_ID = ?", imported.versionId));                       // one target row per regime
        assertEquals(1, count("SELECT COUNT(*) FROM SIX_TARGET t JOIN SIX_INSTRUMENTS i ON i.ID = t.SIX_INSTRU_ID"
                + " WHERE i.VERSION_ID = ? AND t.REGIME = 'UK' AND t.LEGAL_BASIS = 'SAMLA'", imported.versionId));
    }

    @Test
    public void instrumentFile_waitsAt90PercentForItsDelivery() {
        ReglissList instruments = rawList(ImportFileType.SIX_INSTRUMENTS_FILE, "R01");

        ImportedSix imported = deliverAndImport(instruments, fileName("INSTR", DELIVERY),
                instrumentFile(instrument("1001", "XS0000000001", "85", target("Target A", "EU", "Reg 833/2014"))));

        assertEquals(1, exportRequests("SIX_CONFIDENCE_VALUES").size());               // waiting for the confidence step
        assertTrue(jobOpen(imported.jobId));
        assertTrue(fileNames(sixIn()).contains(imported.fileName));                     // stays in IN until the delivery ends
    }

    @Test
    public void unreadableFile_importRefused_nothingWritten() {
        ReglissList instruments = rawList(ImportFileType.SIX_INSTRUMENTS_FILE, "R01");
        String name = fileName("INSTR", DELIVERY);
        deliverToSixIn(name, "<SANCTIONS><INSTRUMENTS><INSTRUMENT>");                    // cut file

        assertThrows(RuntimeException.class, () -> importFile(instruments, name));

        assertEquals(0, count("SELECT COUNT(*) FROM SIX_INSTRUMENTS"));
        assertEquals(0, count("SELECT COUNT(*) FROM SIX_TARGET"));
    }
}
