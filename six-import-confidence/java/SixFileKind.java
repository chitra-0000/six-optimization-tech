package com.bnpp.regliss.importer.six.service;

import java.util.Arrays;
import java.util.Optional;

// TODO: re-add project import for: ImportFileType

/**
 * The SIX raw file types, in ONE place.
 *
 * Everything that differs per file type lives here: how the file is recognised, which table it is
 * imported into, and how its rows are linked to the instrument for the confidence level.
 * The delivery logic (grouping, rollback, confidence merge, keep-alive) is generic and only reads this enum.
 *
 * Adding a 4th file type later = add one line here (+ its marker in allow.six.file.integration,
 * its import service and XSLT as today). Nothing else in the delivery / confidence code changes.
 *
 * Order matters for file-name detection: the first marker found in the name wins
 * (same order as AutomaticFeedAggregator: INSTR, then STRUCT, then OPT).
 */
public enum SixFileKind {

    //          import file type                          name marker  table              column linked to SIX_INSTRUMENTS.CH_VALOR
    INSTRUMENT (ImportFileType.SIX_INSTRUMENTS_FILE,  "INSTR",     "SIX_INSTRUMENTS",  null),   // the confidence SOURCE
    STRUCTURED (ImportFileType.SIX_STRUCTURED_FILE,   "STRUCT",    "SIX_STRUCTURED",   "UNDERLYING_CH"),
    OPTIONS    (ImportFileType.SIX_OPTIONS_FILE,      "OPT",       "SIX_OPTION",       "UNDERLYING_CH");

    private final ImportFileType importFileType;
    private final String fileNameMarker;
    private final String table;
    private final String instrumentLinkColumn;

    SixFileKind(ImportFileType importFileType, String fileNameMarker, String table, String instrumentLinkColumn) {
        this.importFileType = importFileType;
        this.fileNameMarker = fileNameMarker;
        this.table = table;
        this.instrumentLinkColumn = instrumentLinkColumn;
    }

    /** The instrument file: it carries the confidence level, every other file takes it from there. */
    public static SixFileKind source() {
        return INSTRUMENT;
    }

    public boolean isSource() {
        return this == source();
    }

    /** True for the files that receive their confidence level from the instrument file. */
    public boolean receivesConfidence() {
        return instrumentLinkColumn != null;
    }

    public ImportFileType getImportFileType() { return importFileType; }

    public String getTable() { return table; }

    public String getInstrumentLinkColumn() { return instrumentLinkColumn; }

    public static Optional<SixFileKind> of(ImportFileType type) {
        return Arrays.stream(values()).filter(k -> k.importFileType == type).findFirst();
    }

    public static Optional<SixFileKind> ofFileName(String fileName) {
        if (fileName == null) {
            return Optional.empty();
        }
        String upper = fileName.toUpperCase();
        return Arrays.stream(values()).filter(k -> upper.contains(k.fileNameMarker)).findFirst();
    }
}
