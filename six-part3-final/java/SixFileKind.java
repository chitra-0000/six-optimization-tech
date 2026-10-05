package com.bnpp.regliss.importer.six.service;

import java.util.Arrays;
import java.util.Optional;

// TODO: re-add project import for: ImportFileType

/**
 * The SIX raw file types, in ONE place.
 *
 * Everything that differs per file type lives here: how the file is recognised, which tables it is
 * imported into / filtered into, how its rows are linked to the instrument for the confidence level,
 * and how the export filter rules address it. The delivery, confidence and export logic is generic
 * and only reads this enum.
 *
 * Adding a 4th file type later = add one line here (+ its marker in allow.six.file.integration,
 * its import service, XSLT, entity and filtered entity as today).
 *
 * Order matters for file-name detection: the first marker found in the name wins
 * (same order as AutomaticFeedAggregator: INSTR, then STRUCT, then OPT).
 */
public enum SixFileKind {

    INSTRUMENT(ImportFileType.SIX_INSTRUMENTS_FILE, MARKER_INSTR, TABLE_SIX_INSTRUMENTS, null,
            FILE_TYPE_INSTRUMENT, POLLER_TYPE_INSTR, ISIN_INSTR, FK_INSTR,
            TABLE_FILTERED_INSTRUMENTS, INDEX_INSTRUMENT_VERSION, INDEX_TARGET_INSTR),

    STRUCTURED(ImportFileType.SIX_STRUCTURED_FILE, MARKER_STRUCT, TABLE_SIX_STRUCTURED, COL_UNDERLYING_CH,
            FILE_TYPE_STRUCTURED, POLLER_TYPE_STRUCT, ISIN_STRUCT, FK_STRUCT,
            TABLE_FILTERED_STRUCTURED, INDEX_STRUCTURE_VERSION, INDEX_TARGET_STRUCT),

    OPTIONS(ImportFileType.SIX_OPTIONS_FILE, MARKER_OPT, TABLE_SIX_OPTION, COL_UNDERLYING_CH,
            FILE_TYPE_OPTIONS, POLLER_TYPE_OPTIONS, ISIN_OPTIONS, FK_OPTIONS,
            TABLE_FILTERED_OPTIONS, INDEX_OPT_VERSION, INDEX_TARGET_OPT);

    // File name markers
    private static final String MARKER_INSTR = "INSTR";
    private static final String MARKER_STRUCT = "STRUCT";
    private static final String MARKER_OPT = "OPT";

    // Raw table names
    private static final String TABLE_SIX_INSTRUMENTS = "SIX_INSTRUMENTS";
    private static final String TABLE_SIX_STRUCTURED = "SIX_STRUCTURED";
    private static final String TABLE_SIX_OPTION = "SIX_OPTION";
    private static final String COL_UNDERLYING_CH = "UNDERLYING_CH";

    // Filter and poller file types
    private static final String FILE_TYPE_INSTRUMENT = "Instrument File";
    private static final String FILE_TYPE_STRUCTURED = "Structured File";
    private static final String FILE_TYPE_OPTIONS = "Options File";
    private static final String POLLER_TYPE_INSTR = "INSTR";
    private static final String POLLER_TYPE_STRUCT = "STRUCT";
    private static final String POLLER_TYPE_OPTIONS = "OPTIONS";

    // ISIN columns
    private static final String ISIN_INSTR = "i.isin";
    private static final String ISIN_STRUCT = "i.host_isin";
    private static final String ISIN_OPTIONS = "i.isin_option";

    // Foreign key columns
    private static final String FK_INSTR = "SIX_INSTRU_ID";
    private static final String FK_STRUCT = "SIX_STRUCT_ID";
    private static final String FK_OPTIONS = "SIX_OPT_ID";

    // Filtered tables
    private static final String TABLE_FILTERED_INSTRUMENTS = "FILTERED_SIX_INSTRUMENTS";
    private static final String TABLE_FILTERED_STRUCTURED = "FILTERED_SIX_STRUCTURED";
    private static final String TABLE_FILTERED_OPTIONS = "FILTERED_SIX_OPTION";

    // Index names
    private static final String INDEX_INSTRUMENT_VERSION = "IDX_SIX_INSTRUMENT_VERSION";
    private static final String INDEX_STRUCTURE_VERSION = "IDX_SIX_STRUCTURE_VERSION";
    private static final String INDEX_OPT_VERSION = "IDX_SIX_OPT_VERSION";
    private static final String INDEX_TARGET_INSTR = "IDX_SIX_TARGET_INSTR_ID";
    private static final String INDEX_TARGET_STRUCT = "IDX_SIX_TARGET_STRUCT_ID";
    private static final String INDEX_TARGET_OPT = "IDX_SIX_TARGET_OPT_ID";

    private final ImportFileType importFileType;
    private final String fileNameMarker;
    private final String table;
    private final String instrumentLinkColumn;
    private final String filterFileType;
    private final String pollerFileType;
    private final String rootIsinColumn;
    private final String targetFkColumn;
    private final String filteredTable;
    private final String versionIndex;
    private final String targetFkIndex;

    SixFileKind(ImportFileType importFileType, String fileNameMarker, String table, String instrumentLinkColumn,
                String filterFileType, String pollerFileType, String rootIsinColumn, String targetFkColumn,
                String filteredTable, String versionIndex, String targetFkIndex) {
        this.importFileType = importFileType;
        this.fileNameMarker = fileNameMarker;
        this.table = table;
        this.instrumentLinkColumn = instrumentLinkColumn;
        this.filterFileType = filterFileType;
        this.pollerFileType = pollerFileType;
        this.rootIsinColumn = rootIsinColumn;
        this.targetFkColumn = targetFkColumn;
        this.filteredTable = filteredTable;
        this.versionIndex = versionIndex;
        this.targetFkIndex = targetFkIndex;
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

    /** SIX raw table (SIX_INSTRUMENTS ...). */
    public String getTable() { return table; }

    public String getInstrumentLinkColumn() { return instrumentLinkColumn; }

    /** SIX_FILTERS.FILE_TYPE value of the filters written for this file type ("Instrument File" ...). */
    public String getFilterFileType() { return filterFileType; }

    /** SIX_FILTERED_POLLER.FILE_TYPE value ("INSTR", "STRUCT", "OPTIONS"). */
    public String getPollerFileType() { return pollerFileType; }

    /** The ISIN column of this table in the export queries (alias i): i.isin / i.host_isin / i.isin_option. */
    public String getRootIsinColumn() { return rootIsinColumn; }

    /** SIX_TARGET / FILTERED_SIX_TARGET column pointing to this table (SIX_INSTRU_ID ...). */
    public String getTargetFkColumn() { return targetFkColumn; }

    /** FILTERED_SIX_* table written by the export. */
    public String getFilteredTable() { return filteredTable; }

    /** Existing index on VERSION_ID of the raw table (V2_458/459/460). */
    public String getVersionIndex() { return versionIndex; }

    /** Existing index on SIX_TARGET.(SIX_INSTRU_ID | SIX_STRUCT_ID | SIX_OPT_ID) (V2_470). */
    public String getTargetFkIndex() { return targetFkIndex; }

    /**
     * Join hint for "raw table i JOIN SIX_TARGET t": start from the rows of ONE version (version index),
     * then fetch their targets through the FK index. Does not depend on statistics (the tables keep many
     * versions and the newest one is never in the statistics - see the MERGE fix of part 2).
     */
    public String joinHint() {
        return "/*+ LEADING(i) USE_NL(t) INDEX(i " + versionIndex + ") INDEX(t " + targetFkIndex + ") */";
    }

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

    /** The kind whose rows are addressed by this root ISIN column (i.isin -> INSTRUMENT ...). */
    public static Optional<SixFileKind> ofRootIsin(String rootIsinColumn) {
        return Arrays.stream(values()).filter(k -> k.rootIsinColumn.equalsIgnoreCase(rootIsinColumn)).findFirst();
    }
}
