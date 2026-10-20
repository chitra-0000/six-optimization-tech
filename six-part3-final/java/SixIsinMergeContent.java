package com.bnpp.regliss.importer.six.extractor;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// TODO: re-add the project imports in the IDE (Alt+Enter / Optimize Imports) for:
// FilteredInstrumentFile, FilteredStructuredFile

/**
 * Same-ISIN merge (SIX macro rule): what one output list needs, besides its filtered rows, to build ONE record per ISIN.
 *  - activeInstrumentIsins: ISINs of the raw instrument version that have an ACTIVE, valid instrument row (macro:
 *    the structured rows of such an ISIN are never used, neither to select the ISIN nor for its content);
 *  - instrument / structured rows: the other raw rows of the list's ISINs that meet the base conditions of the list's
 *    filters (their content is merged into the record), by ISIN (structured: HOST_ISIN), in raw row order.
 * Read only; built by SixIsinMergeService for one list.
 */
public final class SixIsinMergeContent {

    private static final SixIsinMergeContent NONE = new SixIsinMergeContent(Collections.emptySet(),
            Collections.emptyMap(), Collections.emptyMap());

    private final Set<String> activeInstrumentIsins;
    private final Map<String, List<FilteredInstrumentFile>> instrumentRows;
    private final Map<String, List<FilteredStructuredFile>> structuredRows;

    public SixIsinMergeContent(Set<String> activeInstrumentIsins, Map<String, List<FilteredInstrumentFile>> instrumentRows,
                               Map<String, List<FilteredStructuredFile>> structuredRows) {
        this.activeInstrumentIsins = activeInstrumentIsins == null ? Collections.emptySet()
                : Collections.unmodifiableSet(new HashSet<>(activeInstrumentIsins));
        this.instrumentRows = instrumentRows == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(instrumentRows));
        this.structuredRows = structuredRows == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(structuredRows));
    }

    /** Nothing to merge and no active instrument known (raw rows not available). */
    public static SixIsinMergeContent none() {
        return NONE;
    }

    /** True when the raw instrument file has an active, valid row for this ISIN (its structured rows are ignored). */
    public boolean hasActiveInstrument(String isin) {
        return isin != null && activeInstrumentIsins.contains(isin);
    }

    /** Raw instrument rows of this ISIN meeting the base conditions (empty when none). */
    public List<FilteredInstrumentFile> instrumentRows(String isin) {
        List<FilteredInstrumentFile> rows = isin == null ? null : instrumentRows.get(isin);
        return rows == null ? Collections.emptyList() : Collections.unmodifiableList(rows);
    }

    /** Raw structured rows of this HOST_ISIN meeting the base conditions (empty when none). */
    public List<FilteredStructuredFile> structuredRows(String hostIsin) {
        List<FilteredStructuredFile> rows = hostIsin == null ? null : structuredRows.get(hostIsin);
        return rows == null ? Collections.emptyList() : Collections.unmodifiableList(rows);
    }

    public int activeInstrumentCount() {
        return activeInstrumentIsins.size();
    }

    public int mergedIsinCount() {
        Set<String> isins = new HashSet<>(instrumentRows.keySet());
        isins.addAll(structuredRows.keySet());
        return isins.size();
    }
}
