# SIX same-ISIN merge (macro alignment) - delta

New classes (package com.bnpp.regliss.importer.six.extractor):
- SixIsinMergeService   - per raw version: active instrument ISINs (macro case B), ISINs with several raw rows;
                          per list: raw rows of these ISINs meeting the BASE conditions of the list's filters
- SixIsinMergeContent   - what one list needs to merge (read only)
- SixIsinRecordMerger   - one record per ISIN (replaces the reduce / remove-duplicate methods of the XML poller)

Changed classes:
- DynamicSqlBuilder            - buildBaseWhereClause / isSelectionSubFilter (sub-filters with a "- REGIME" or
                                 "all targets" row are left out as a whole)
- SixFileFilterService         - instrumentContentRows / structuredContentRows (base conditions + ISIN IN, 1000 per query,
                                 same CMIC / E014071 exclusions)
- SixXmlGenerationPoller       - loads the content, uses SixIsinRecordMerger; old reduce/remove-duplicate methods removed
- FilteredInstrumentFile       - @Transient: description, underlyingCh, underlyingIsin, underlyingGk,
                                 underlyingIssuerShortname, mergedTextTargets (no DB column)
- FilteredStructuredFile       - @Transient: mergedTextTargets (no DB column)
- InstrumentRecordTypeBuilder  - notes in macro order (+ merged structured fields), programs sorted by full label
                                 (case-insensitive, unique label+target), rationale / reason from all merged rows, PORTUGAL
- StructuredRecordTypeBuilder  - same, plus one CUSIP code per merged value

No DB change, no XSD change, import / filter export / cleanup / regeneration unchanged.
