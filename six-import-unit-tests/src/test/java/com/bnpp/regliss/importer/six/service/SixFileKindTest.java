package com.bnpp.regliss.importer.six.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

// TODO: re-add project imports for: ImportFileType

public class SixFileKindTest {

    @Test
    public void sourceAndConfidence() {
        assertSame(SixFileKind.INSTRUMENT, SixFileKind.source());
        assertTrue(SixFileKind.INSTRUMENT.isSource());
        assertFalse(SixFileKind.STRUCTURED.isSource());
        assertFalse(SixFileKind.INSTRUMENT.receivesConfidence());
        assertTrue(SixFileKind.STRUCTURED.receivesConfidence());
        assertTrue(SixFileKind.OPTIONS.receivesConfidence());
    }

    @Test
    public void getters() {
        SixFileKind k = SixFileKind.STRUCTURED;
        assertSame(ImportFileType.SIX_STRUCTURED_FILE, k.getImportFileType());
        assertEquals("SIX_STRUCTURED", k.getTable());
        assertEquals("UNDERLYING_CH", k.getInstrumentLinkColumn());
        assertEquals("Structured File", k.getFilterFileType());
        assertEquals("STRUCT", k.getPollerFileType());
        assertEquals("i.host_isin", k.getRootIsinColumn());
        assertEquals("SIX_STRUCT_ID", k.getTargetFkColumn());
        assertEquals("FILTERED_SIX_STRUCTURED", k.getFilteredTable());
        assertEquals("IDX_SIX_STRUCTURE_VERSION", k.getVersionIndex());
        assertEquals("IDX_SIX_TARGET_STRUCT_ID", k.getTargetFkIndex());
    }

    @Test
    public void hints() {
        assertEquals("/*+ LEADING(i) USE_NL(t) INDEX(i IDX_SIX_INSTRUMENT_VERSION) INDEX(t IDX_SIX_TARGET_INSTR_ID) */",
                SixFileKind.INSTRUMENT.joinHint());
        assertEquals("/*+ LEADING(i t ex) USE_NL(t) USE_HASH(ex) SWAP_JOIN_INPUTS(ex) NO_MERGE(ex) NO_PUSH_PRED(ex) INDEX(i "
                + "IDX_SIX_OPT_VERSION) INDEX(t IDX_SIX_TARGET_OPT_ID) */", SixFileKind.OPTIONS.joinHintWithExclusion());
    }

    @Test
    public void lookups() {
        assertSame(SixFileKind.OPTIONS, SixFileKind.of(ImportFileType.SIX_OPTIONS_FILE).get());
        assertFalse(SixFileKind.of(ImportFileType.SIX_MAIN_FILE).isPresent());
        assertFalse(SixFileKind.ofFileName(null).isPresent());
        assertSame(SixFileKind.INSTRUMENT, SixFileKind.ofFileName("gsm_1_202_instr_full_20261004_101500.xml").get());
        assertSame(SixFileKind.STRUCTURED, SixFileKind.ofFileName("GSM_STRUCT_FULL.xml").get());
        assertFalse(SixFileKind.ofFileName("OTHER.xml").isPresent());
        assertSame(SixFileKind.OPTIONS, SixFileKind.ofRootIsin("I.ISIN_OPTION").get());
        assertFalse(SixFileKind.ofRootIsin("i.other").isPresent());
    }
}
