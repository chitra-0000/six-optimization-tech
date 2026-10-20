package com.bnpp.regliss.importer.six.extractor;

import org.apache.commons.lang3.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// TODO: re-add the project imports in the IDE (Alt+Enter / Optimize Imports) for:
// FilteredInstrumentFile, FilteredStructuredFile, FilteredSixTarget

/**
 * One record per ISIN in a CONVERTER file, as the SIX macro (SIX2ReglissXML Converter) builds it. Replaces the former
 * "remove duplicated ISIN inside files / between files" rules of the XML step.
 *
 * For each ISIN of the output list (instrument ISIN, structured HOST_ISIN):
 *  - rows used: the filtered rows of the list (they pass every filter) first, then the other raw rows of the ISIN that
 *    meet the base conditions of the list's filters (SixIsinMergeContent), in that order;
 *  - structured rows of an ISIN with an ACTIVE, valid instrument row are never used (macro: not imported);
 *  - the record is an INSTRUMENT record when the ISIN has an instrument row, the structured fields being added to it;
 *    otherwise a STRUCTURED record;
 *  - each field: distinct non-blank values of the rows, in row order, joined with " - " (dates as yyyy-MM-dd);
 *  - programs / regimes (sixTargets): targets of the FILTERED rows only (e.g. only UK for the UK list);
 *  - sanctions rationale / reason for change (mergedTextTargets): targets of every row used.
 * An ISIN with one row and nothing to merge keeps the former rule (only its dates are formatted).
 */
public class SixIsinRecordMerger {

    private static final String DELIMITER = " - ";
    private static final DateTimeFormatter INPUT_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter OUTPUT_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    /** Instrument record fields: instrument value, value of a merged structured row (null: none), setter, date. */
    private static final List<MergedField<FilteredInstrumentFile>> INSTRUMENT_FIELDS = Arrays.asList(
            text(FilteredInstrumentFile::getLinkEntity, null, FilteredInstrumentFile::setLinkEntity),
            // macro: the HOST_GK of a structured row is read into the LINK_CSID column
            text(FilteredInstrumentFile::getLinkCsid, FilteredStructuredFile::getHostGk, FilteredInstrumentFile::setLinkCsid),
            text(FilteredInstrumentFile::getNameDirectIssuer, null, FilteredInstrumentFile::setNameDirectIssuer),
            text(FilteredInstrumentFile::getSanctionedParentEntity, null, FilteredInstrumentFile::setSanctionedParentEntity),
            text(FilteredInstrumentFile::getConfidenceLevel, FilteredStructuredFile::getConfidenceLevel, FilteredInstrumentFile::setConfidenceLevel),
            text(FilteredInstrumentFile::getInstrName, null, FilteredInstrumentFile::setInstrName),
            text(FilteredInstrumentFile::getFisn, FilteredStructuredFile::getFisn, FilteredInstrumentFile::setFisn),
            date(FilteredInstrumentFile::getIndicativeIssueDate, FilteredStructuredFile::getIndicativeIssueDate, FilteredInstrumentFile::setIndicativeIssueDate),
            text(FilteredInstrumentFile::getInstrumentType, FilteredStructuredFile::getInstrumentType, FilteredInstrumentFile::setInstrumentType),
            text(FilteredInstrumentFile::getSanctionsRelevantAssetClass, null, FilteredInstrumentFile::setSanctionsRelevantAssetClass),
            text(FilteredInstrumentFile::getMainInstrument, null, FilteredInstrumentFile::setMainInstrument),
            text(FilteredInstrumentFile::getEquityTypeOfIssuance, null, FilteredInstrumentFile::setEquityTypeOfIssuance),
            text(FilteredInstrumentFile::getDenominationCurrency, FilteredStructuredFile::getDenominationCurrency, FilteredInstrumentFile::setDenominationCurrency),
            date(FilteredInstrumentFile::getMaturityDate, FilteredStructuredFile::getMaturityDate, FilteredInstrumentFile::setMaturityDate),
            text(FilteredInstrumentFile::getDebtLifetimeInDays, null, FilteredInstrumentFile::setDebtLifetimeInDays),
            text(FilteredInstrumentFile::getActiveFlag, FilteredStructuredFile::getActiveFlag, FilteredInstrumentFile::setActiveFlag),
            date(FilteredInstrumentFile::getIssueDate, FilteredStructuredFile::getIssueDate, FilteredInstrumentFile::setIssueDate),
            date(FilteredInstrumentFile::getCapitalChangeDate, null, FilteredInstrumentFile::setCapitalChangeDate),
            text(FilteredInstrumentFile::getDescription, FilteredStructuredFile::getDescription, FilteredInstrumentFile::setDescription),
            text(FilteredInstrumentFile::getUnderlyingCh, FilteredStructuredFile::getUnderlyingCh, FilteredInstrumentFile::setUnderlyingCh),
            text(FilteredInstrumentFile::getUnderlyingIsin, FilteredStructuredFile::getUnderlyingIsin, FilteredInstrumentFile::setUnderlyingIsin),
            text(FilteredInstrumentFile::getUnderlyingGk, FilteredStructuredFile::getUnderlyingGk, FilteredInstrumentFile::setUnderlyingGk),
            text(FilteredInstrumentFile::getUnderlyingIssuerShortname, FilteredStructuredFile::getUnderlyingIssuerShortname,
                    FilteredInstrumentFile::setUnderlyingIssuerShortname),
            text(FilteredInstrumentFile::getSedol, FilteredStructuredFile::getSedol, FilteredInstrumentFile::setSedol),
            text(FilteredInstrumentFile::getCusip, FilteredStructuredFile::getCusip, FilteredInstrumentFile::setCusip),
            text(FilteredInstrumentFile::getCins, FilteredStructuredFile::getCins, FilteredInstrumentFile::setCins),
            text(FilteredInstrumentFile::getAustrian, FilteredStructuredFile::getAustrian, FilteredInstrumentFile::setAustrian),
            text(FilteredInstrumentFile::getBelgian, FilteredStructuredFile::getBelgian, FilteredInstrumentFile::setBelgian),
            text(FilteredInstrumentFile::getCanadian, FilteredStructuredFile::getCanadian, FilteredInstrumentFile::setCanadian),
            text(FilteredInstrumentFile::getGerman, FilteredStructuredFile::getGerman, FilteredInstrumentFile::setGerman),
            text(FilteredInstrumentFile::getDenmark, FilteredStructuredFile::getDenmark, FilteredInstrumentFile::setDenmark),
            text(FilteredInstrumentFile::getFranceRga, FilteredStructuredFile::getFranceRga, FilteredInstrumentFile::setFranceRga),
            text(FilteredInstrumentFile::getFranceEuroclear, FilteredStructuredFile::getFranceEuroClear, FilteredInstrumentFile::setFranceEuroclear),
            text(FilteredInstrumentFile::getItalian, FilteredStructuredFile::getItalian, FilteredInstrumentFile::setItalian),
            text(FilteredInstrumentFile::getJapaneseCurrent, FilteredStructuredFile::getJapaneseCurrent, FilteredInstrumentFile::setJapaneseCurrent),
            text(FilteredInstrumentFile::getJapaneseNew, FilteredStructuredFile::getJapaneseNew, FilteredInstrumentFile::setJapaneseNew),
            text(FilteredInstrumentFile::getLuxembourg, FilteredStructuredFile::getLuxembourg, FilteredInstrumentFile::setLuxembourg),
            text(FilteredInstrumentFile::getNetherland, FilteredStructuredFile::getNetherland, FilteredInstrumentFile::setNetherland),
            text(FilteredInstrumentFile::getNorwegian, FilteredStructuredFile::getNorwegian, FilteredInstrumentFile::setNorwegian),
            text(FilteredInstrumentFile::getSwedish, FilteredStructuredFile::getSwedish, FilteredInstrumentFile::setSwedish),
            text(FilteredInstrumentFile::getXsIntNumber, FilteredStructuredFile::getXsIntNumber, FilteredInstrumentFile::setXsIntNumber),
            text(FilteredInstrumentFile::getPortugal, FilteredStructuredFile::getPortugal, FilteredInstrumentFile::setPortugal),
            text(FilteredInstrumentFile::getSouthKorea, FilteredStructuredFile::getSouthKorea, FilteredInstrumentFile::setSouthKorea),
            text(FilteredInstrumentFile::getHongKong, FilteredStructuredFile::getHongKong, FilteredInstrumentFile::setHongKong),
            text(FilteredInstrumentFile::getFigiGlobalId, FilteredStructuredFile::getFigiGlobalId, FilteredInstrumentFile::setFigiGlobalId));

    /** Structured record fields (structured rows only). */
    private static final List<MergedField<FilteredStructuredFile>> STRUCTURED_FIELDS = Arrays.asList(
            text(FilteredStructuredFile::getHostCh, null, FilteredStructuredFile::setHostCh),
            text(FilteredStructuredFile::getHostGk, null, FilteredStructuredFile::setHostGk),
            text(FilteredStructuredFile::getHostIssuerShortname, null, FilteredStructuredFile::setHostIssuerShortname),
            text(FilteredStructuredFile::getConfidenceLevel, null, FilteredStructuredFile::setConfidenceLevel),
            text(FilteredStructuredFile::getDescription, null, FilteredStructuredFile::setDescription),
            text(FilteredStructuredFile::getFisn, null, FilteredStructuredFile::setFisn),
            date(FilteredStructuredFile::getIndicativeIssueDate, null, FilteredStructuredFile::setIndicativeIssueDate),
            text(FilteredStructuredFile::getInstrumentType, null, FilteredStructuredFile::setInstrumentType),
            text(FilteredStructuredFile::getDenominationCurrency, null, FilteredStructuredFile::setDenominationCurrency),
            date(FilteredStructuredFile::getMaturityDate, null, FilteredStructuredFile::setMaturityDate),
            text(FilteredStructuredFile::getActiveFlag, null, FilteredStructuredFile::setActiveFlag),
            date(FilteredStructuredFile::getIssueDate, null, FilteredStructuredFile::setIssueDate),
            text(FilteredStructuredFile::getUnderlyingCh, null, FilteredStructuredFile::setUnderlyingCh),
            text(FilteredStructuredFile::getUnderlyingIsin, null, FilteredStructuredFile::setUnderlyingIsin),
            text(FilteredStructuredFile::getUnderlyingGk, null, FilteredStructuredFile::setUnderlyingGk),
            text(FilteredStructuredFile::getUnderlyingIssuerShortname, null, FilteredStructuredFile::setUnderlyingIssuerShortname),
            text(FilteredStructuredFile::getSedol, null, FilteredStructuredFile::setSedol),
            text(FilteredStructuredFile::getCusip, null, FilteredStructuredFile::setCusip),
            text(FilteredStructuredFile::getCins, null, FilteredStructuredFile::setCins),
            text(FilteredStructuredFile::getAustrian, null, FilteredStructuredFile::setAustrian),
            text(FilteredStructuredFile::getBelgian, null, FilteredStructuredFile::setBelgian),
            text(FilteredStructuredFile::getCanadian, null, FilteredStructuredFile::setCanadian),
            text(FilteredStructuredFile::getGerman, null, FilteredStructuredFile::setGerman),
            text(FilteredStructuredFile::getDenmark, null, FilteredStructuredFile::setDenmark),
            text(FilteredStructuredFile::getFranceRga, null, FilteredStructuredFile::setFranceRga),
            text(FilteredStructuredFile::getFranceEuroClear, null, FilteredStructuredFile::setFranceEuroClear),
            text(FilteredStructuredFile::getItalian, null, FilteredStructuredFile::setItalian),
            text(FilteredStructuredFile::getJapaneseCurrent, null, FilteredStructuredFile::setJapaneseCurrent),
            text(FilteredStructuredFile::getJapaneseNew, null, FilteredStructuredFile::setJapaneseNew),
            text(FilteredStructuredFile::getLuxembourg, null, FilteredStructuredFile::setLuxembourg),
            text(FilteredStructuredFile::getNetherland, null, FilteredStructuredFile::setNetherland),
            text(FilteredStructuredFile::getNorwegian, null, FilteredStructuredFile::setNorwegian),
            text(FilteredStructuredFile::getSwedish, null, FilteredStructuredFile::setSwedish),
            text(FilteredStructuredFile::getXsIntNumber, null, FilteredStructuredFile::setXsIntNumber),
            text(FilteredStructuredFile::getPortugal, null, FilteredStructuredFile::setPortugal),
            text(FilteredStructuredFile::getSouthKorea, null, FilteredStructuredFile::setSouthKorea),
            text(FilteredStructuredFile::getHongKong, null, FilteredStructuredFile::setHongKong),
            text(FilteredStructuredFile::getFigiGlobalId, null, FilteredStructuredFile::setFigiGlobalId));

    /** Records of one output list: one per ISIN. Mutable lists (the duplicate-by-sanction rule removes from them). */
    public static final class Result {
        private final List<FilteredInstrumentFile> instruments;
        private final List<FilteredStructuredFile> structured;

        Result(List<FilteredInstrumentFile> instruments, List<FilteredStructuredFile> structured) {
            this.instruments = instruments;
            this.structured = structured;
        }

        public List<FilteredInstrumentFile> getInstruments() {
            return instruments;
        }

        public List<FilteredStructuredFile> getStructured() {
            return structured;
        }
    }

    /** ISINs of the output list: instrument ISINs and structured HOST_ISINs of its filtered rows. */
    public static Set<String> outputIsins(List<FilteredInstrumentFile> instruments, List<FilteredStructuredFile> structured) {
        Set<String> isins = new LinkedHashSet<>();
        instruments.stream().filter(Objects::nonNull).map(FilteredInstrumentFile::getIsin).filter(Objects::nonNull).forEach(isins::add);
        structured.stream().filter(Objects::nonNull).map(FilteredStructuredFile::getHostIsin).filter(Objects::nonNull).forEach(isins::add);
        return isins;
    }

    public Result merge(List<FilteredInstrumentFile> filteredInstruments, List<FilteredStructuredFile> filteredStructured,
                        SixIsinMergeContent content, String listReference) {
        Map<String, List<FilteredInstrumentFile>> instrumentsByIsin = group(filteredInstruments, FilteredInstrumentFile::getIsin);
        Map<String, List<FilteredStructuredFile>> structuredByIsin = group(filteredStructured, FilteredStructuredFile::getHostIsin);
        // macro: no structured row is used for an ISIN that has an active, valid instrument row
        structuredByIsin.keySet().removeIf(content::hasActiveInstrument);

        List<FilteredInstrumentFile> instruments = new ArrayList<>();
        List<FilteredStructuredFile> structured = new ArrayList<>();
        for (Map.Entry<String, List<FilteredInstrumentFile>> isin : instrumentsByIsin.entrySet()) {
            List<FilteredStructuredFile> sameIsinStructured = structuredByIsin.remove(isin.getKey());
            instruments.add(instrumentRecord(isin.getValue(), orEmpty(sameIsinStructured), content.instrumentRows(isin.getKey()),
                    content.structuredRows(isin.getKey())));
        }
        for (Map.Entry<String, List<FilteredStructuredFile>> isin : structuredByIsin.entrySet()) {
            List<FilteredInstrumentFile> instrumentRows = content.instrumentRows(isin.getKey());
            if (instrumentRows.isEmpty()) {
                structured.add(structuredRecord(isin.getValue(), content.structuredRows(isin.getKey())));
            } else {
                instruments.add(instrumentRecord(Collections.emptyList(), isin.getValue(), instrumentRows,
                        content.structuredRows(isin.getKey())));
            }
        }
        checkUniqueReferences(instruments, FilteredInstrumentFile::getChValor, "Instruments", listReference);
        checkUniqueReferences(structured, FilteredStructuredFile::getHostCh, "Structure", listReference);
        return new Result(instruments, structured);
    }

    // ------------------------------------------------------------------------------------------ one record

    /** Instrument record of one ISIN; filteredInstruments + extraInstruments is never empty. */
    private FilteredInstrumentFile instrumentRecord(List<FilteredInstrumentFile> filteredInstruments,
                                                    List<FilteredStructuredFile> filteredStructured,
                                                    List<FilteredInstrumentFile> extraInstruments,
                                                    List<FilteredStructuredFile> extraStructured) {
        List<FilteredInstrumentFile> instrumentRows = distinctRows(filteredInstruments, extraInstruments);
        List<FilteredStructuredFile> structuredRows = distinctRows(filteredStructured, extraStructured);
        FilteredInstrumentFile merged = instrumentRows.get(0);
        if (instrumentRows.size() == 1 && structuredRows.isEmpty()) {
            formatDates(merged);
            return merged;
        }
        for (MergedField<FilteredInstrumentFile> field : INSTRUMENT_FIELDS) {
            field.merge(merged, instrumentRows, structuredRows);
        }
        List<FilteredSixTarget> programTargets = targetsOf(filteredInstruments, FilteredInstrumentFile::getSixTargets);
        programTargets.addAll(targetsOf(filteredStructured, FilteredStructuredFile::getSixTargets));
        List<FilteredSixTarget> textTargets = targetsOf(instrumentRows, FilteredInstrumentFile::getSixTargets);
        textTargets.addAll(targetsOf(structuredRows, FilteredStructuredFile::getSixTargets));
        merged.setSixTargets(programTargets);
        merged.setMergedTextTargets(textTargets);
        return merged;
    }

    /** Structured record of one HOST_ISIN (no instrument row); filteredStructured is never empty. */
    private FilteredStructuredFile structuredRecord(List<FilteredStructuredFile> filteredStructured,
                                                    List<FilteredStructuredFile> extraStructured) {
        List<FilteredStructuredFile> rows = distinctRows(filteredStructured, extraStructured);
        FilteredStructuredFile merged = rows.get(0);
        if (rows.size() == 1) {
            merged.setIndicativeIssueDate(formatDates(Stream.of(merged.getIndicativeIssueDate())));
            merged.setMaturityDate(formatDates(Stream.of(merged.getMaturityDate())));
            merged.setIssueDate(formatDates(Stream.of(merged.getIssueDate())));
            return merged;
        }
        for (MergedField<FilteredStructuredFile> field : STRUCTURED_FIELDS) {
            field.merge(merged, rows, Collections.emptyList());
        }
        merged.setSixTargets(targetsOf(filteredStructured, FilteredStructuredFile::getSixTargets));
        merged.setMergedTextTargets(targetsOf(rows, FilteredStructuredFile::getSixTargets));
        return merged;
    }

    /** Former rule of a record with one row: only its dates are formatted (yyyyMMdd -> yyyy-MM-dd). */
    private void formatDates(FilteredInstrumentFile file) {
        file.setIndicativeIssueDate(formatDates(Stream.of(file.getIndicativeIssueDate())));
        file.setMaturityDate(formatDates(Stream.of(file.getMaturityDate())));
        file.setIssueDate(formatDates(Stream.of(file.getIssueDate())));
        file.setCapitalChangeDate(formatDates(Stream.of(file.getCapitalChangeDate())));
    }

    // ------------------------------------------------------------------------------------------ helpers

    /** Rows grouped by ISIN in their order; rows without ISIN are left out (former rule). */
    private static <T> Map<String, List<T>> group(List<T> rows, Function<T, String> isinOf) {
        Map<String, List<T>> byIsin = new LinkedHashMap<>();
        if (rows != null) {
            rows.stream().filter(Objects::nonNull).filter(r -> isinOf.apply(r) != null)
                    .forEach(r -> byIsin.computeIfAbsent(isinOf.apply(r), k -> new ArrayList<>()).add(r));
        }
        return byIsin;
    }

    /** Filtered rows first, then the extra rows; the same instance is never used twice. */
    private static <T> List<T> distinctRows(List<T> filtered, List<T> extra) {
        Set<T> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<T> rows = new ArrayList<>();
        Stream.concat(filtered.stream(), extra.stream()).filter(Objects::nonNull).filter(seen::add).forEach(rows::add);
        return rows;
    }

    private static <T> List<T> orEmpty(List<T> rows) {
        return rows == null ? Collections.emptyList() : rows;
    }

    /** Targets of the rows, in row order (new list: the rows' own target lists are not changed). */
    private static <T> List<FilteredSixTarget> targetsOf(List<T> rows, Function<T, List<FilteredSixTarget>> targetsOfRow) {
        List<FilteredSixTarget> targets = new ArrayList<>();
        for (T row : rows) {
            List<FilteredSixTarget> own = targetsOfRow.apply(row);
            if (own != null) {
                targets.addAll(own);
            }
        }
        return targets;
    }

    /** Distinct non-blank values in order, joined with " - " ("" when none, as before). */
    static String join(Stream<String> values) {
        return values.filter(StringUtils::isNotBlank).distinct().collect(Collectors.joining(DELIMITER));
    }

    /** Same for dates: each date formatted yyyy-MM-dd first. */
    static String formatDates(Stream<String> values) {
        return join(values.filter(StringUtils::isNotBlank).map(SixIsinRecordMerger::formatDate));
    }

    /** yyyyMMdd -> yyyy-MM-dd; a value in another format is kept as it is (a raw row must not stop the whole file). */
    private static String formatDate(String value) {
        try {
            return LocalDate.parse(value.trim(), INPUT_FORMATTER).format(OUTPUT_FORMATTER);
        } catch (DateTimeParseException e) {
            return value;
        }
    }

    private static <T> void checkUniqueReferences(List<T> records, Function<T, String> referenceOf, String what, String listReference) {
        Map<String, T> byReference = new HashMap<>();
        for (T rec : records) {
            if (byReference.putIfAbsent(referenceOf.apply(rec), rec) != null) {
                throw new IllegalStateException(String.format("Duplicate external reference found while applying generic filters in %s"
                        + " - %s for list ref - %s", what, referenceOf.apply(rec), listReference));
            }
        }
    }

    private static <T> MergedField<T> text(Function<T, String> getter, Function<FilteredStructuredFile, String> structuredGetter,
                                     BiConsumer<T, String> setter) {
        return new MergedField<>(getter, structuredGetter, setter, false);
    }

    private static <T> MergedField<T> date(Function<T, String> getter, Function<FilteredStructuredFile, String> structuredGetter,
                                     BiConsumer<T, String> setter) {
        return new MergedField<>(getter, structuredGetter, setter, true);
    }

    /** One merged field: values of the record's own rows, then of the merged structured rows. */
    private static final class MergedField<T> {
        private final Function<T, String> getter;
        private final Function<FilteredStructuredFile, String> structuredGetter;
        private final BiConsumer<T, String> setter;
        private final boolean isDate;

        MergedField(Function<T, String> getter, Function<FilteredStructuredFile, String> structuredGetter, BiConsumer<T, String> setter,
              boolean isDate) {
            this.getter = getter;
            this.structuredGetter = structuredGetter;
            this.setter = setter;
            this.isDate = isDate;
        }

        void merge(T target, List<T> rows, List<FilteredStructuredFile> structuredRows) {
            Stream<String> values = rows.stream().map(getter);
            if (structuredGetter != null) {
                values = Stream.concat(values, structuredRows.stream().map(structuredGetter));
            }
            List<String> all = values.collect(Collectors.toList());   // read every row before the record is changed
            setter.accept(target, isDate ? formatDates(all.stream()) : join(all.stream()));
        }
    }
}
