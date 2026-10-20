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

    INSTRUMENT(ImportFileType.SIX_INSTRUMENTS_FILE,
            new Names("INSTR", "Instrument File", "INSTR"),
            new Tables("SIX_INSTRUMENTS", null, "i.isin", "SIX_INSTRU_ID",
                    "FILTERED_SIX_INSTRUMENTS", "IDX_SIX_INSTRUMENT_VERSION", "IDX_SIX_TARGET_INSTR_ID")),

    STRUCTURED(ImportFileType.SIX_STRUCTURED_FILE,
            new Names("STRUCT", "Structured File", "STRUCT"),
            new Tables("SIX_STRUCTURED", "UNDERLYING_CH", "i.host_isin", "SIX_STRUCT_ID",
                    "FILTERED_SIX_STRUCTURED", "IDX_SIX_STRUCTURE_VERSION", "IDX_SIX_TARGET_STRUCT_ID")),

    OPTIONS(ImportFileType.SIX_OPTIONS_FILE,
            new Names("OPT", "Options File", "OPTIONS"),
            new Tables("SIX_OPTION", "UNDERLYING_CH", "i.isin_option", "SIX_OPT_ID",
                    "FILTERED_SIX_OPTION", "IDX_SIX_OPT_VERSION", "IDX_SIX_TARGET_OPT_ID"));

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

    SixFileKind(ImportFileType importFileType, Names names, Tables tables) {
        this.importFileType = importFileType;
        this.fileNameMarker = names.fileNameMarker;
        this.filterFileType = names.filterFileType;
        this.pollerFileType = names.pollerFileType;
        this.table = tables.table;
        this.instrumentLinkColumn = tables.instrumentLinkColumn;
        this.rootIsinColumn = tables.rootIsinColumn;
        this.targetFkColumn = tables.targetFkColumn;
        this.filteredTable = tables.filteredTable;
        this.versionIndex = tables.versionIndex;
        this.targetFkIndex = tables.targetFkIndex;
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

    /**
     * Same join hint when the query also joins the exclusion-list ISINs (view "ex", filters "included / not included
     * in exclusion"): the view is computed ONCE and hash-joined (built in memory, probed in the order of i / t), never
     * re-run for each row (NO_MERGE / NO_PUSH_PRED keep it a separate, one-time view).
     */
    public String joinHintWithExclusion() {
        return "/*+ LEADING(i t ex) USE_NL(t) USE_HASH(ex) SWAP_JOIN_INPUTS(ex) NO_MERGE(ex) NO_PUSH_PRED(ex) INDEX(i "
                + versionIndex + ") INDEX(t " + targetFkIndex + ") */";
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

    /** How a file type is named: marker in the SIX file name, SIX_FILTERS.FILE_TYPE, SIX_FILTERED_POLLER.FILE_TYPE. */
    static final class Names {
        private final String fileNameMarker;
        private final String filterFileType;
        private final String pollerFileType;

        Names(String fileNameMarker, String filterFileType, String pollerFileType) {
            this.fileNameMarker = fileNameMarker;
            this.filterFileType = filterFileType;
            this.pollerFileType = pollerFileType;
        }
    }

    /** Tables, columns and indexes of a file type (constants only, never from input). */
    static final class Tables {
        private final String table;
        private final String instrumentLinkColumn;
        private final String rootIsinColumn;
        private final String targetFkColumn;
        private final String filteredTable;
        private final String versionIndex;
        private final String targetFkIndex;

        Tables(String table, String instrumentLinkColumn, String rootIsinColumn, String targetFkColumn,
               String filteredTable, String versionIndex, String targetFkIndex) {
            this.table = table;
            this.instrumentLinkColumn = instrumentLinkColumn;
            this.rootIsinColumn = rootIsinColumn;
            this.targetFkColumn = targetFkColumn;
            this.filteredTable = filteredTable;
            this.versionIndex = versionIndex;
            this.targetFkIndex = targetFkIndex;
        }
    }
}
