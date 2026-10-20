package com.bnpp.regliss.importer.six.extractor;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

// TODO: re-add project imports for: FilteredInstrumentFile, FilteredStructuredFile, FilteredSixTarget

public class SixIsinRecordMergerTest {

    private final SixIsinRecordMerger merger = new SixIsinRecordMerger();

    @Test
    public void noRows_nothing() {
        SixIsinRecordMerger.Result result = merger.merge(null, null, SixIsinMergeContent.none(), "951");
        assertTrue(result.getInstruments().isEmpty());
        assertTrue(result.getStructured().isEmpty());
    }

    @Test
    public void rowsWithoutTargets_merged() {
        FilteredInstrumentFile a = new FilteredInstrumentFile();
        a.setIsin("X");
        a.setChValor("1");
        a.setSixTargets(null);
        a.setDescription("first");
        FilteredInstrumentFile b = new FilteredInstrumentFile();
        b.setIsin("X");
        b.setChValor("1");
        b.setDescription("second");
        FilteredSixTarget t = new FilteredSixTarget();
        t.setRegime("UK");
        b.setSixTargets(Collections.singletonList(t));

        SixIsinRecordMerger.Result result = merger.merge(Arrays.asList(a, b), Collections.emptyList(), SixIsinMergeContent.none(), "951");

        FilteredInstrumentFile merged = result.getInstruments().get(0);
        assertSame(a, merged);
        assertEquals("first - second", merged.getDescription());
        assertEquals(Collections.singletonList(t), merged.getSixTargets());
        assertEquals(Collections.singletonList(t), merged.getMergedTextTargets());
    }

    @Test
    public void twoStructuredIsinsWithTheSameHostCh_fail() {
        FilteredStructuredFile a = new FilteredStructuredFile();
        a.setHostIsin("A");
        a.setHostCh("CH");
        FilteredStructuredFile b = new FilteredStructuredFile();
        b.setHostIsin("B");
        b.setHostCh("CH");
        List<FilteredStructuredFile> rows = Arrays.asList(a, b);
        SixIsinMergeContent none = SixIsinMergeContent.none();
        List<FilteredInstrumentFile> noInstrument = Collections.emptyList();
        try {
            merger.merge(noInstrument, rows, none, "235");
            fail();
        } catch (IllegalStateException e) {
            assertEquals("Duplicate external reference found while applying generic filters in Structure - CH for list ref - 235",
                    e.getMessage());
        }
    }

    @Test
    public void structuredIsinWithInstrumentRowsInTheRawFile_becomesAnInstrumentRecord() {
        FilteredStructuredFile s = new FilteredStructuredFile();
        s.setHostIsin("X");
        s.setHostGk("GK1");
        FilteredInstrumentFile raw = new FilteredInstrumentFile();
        raw.setIsin("X");
        raw.setChValor("9");
        Map<String, List<FilteredInstrumentFile>> instruments = new HashMap<>();
        instruments.put("X", Collections.singletonList(raw));
        SixIsinMergeContent content = new SixIsinMergeContent(Collections.emptySet(), instruments, Collections.emptyMap());

        SixIsinRecordMerger.Result result = merger.merge(Collections.emptyList(), Collections.singletonList(s), content, "951");

        assertTrue(result.getStructured().isEmpty());
        assertEquals("GK1", result.getInstruments().get(0).getLinkCsid());          // macro: HOST_GK read as LINK_CSID
    }

    @Test
    public void joinAndDates() {
        assertEquals("a - b", SixIsinRecordMerger.join(Stream.of("a", " ", null, "b", "a")));
        assertEquals("", SixIsinRecordMerger.join(Stream.empty()));
        assertEquals("2026-10-04 - 2026/10/05", SixIsinRecordMerger.formatDates(Stream.of("20261004", null, "2026/10/05", " 20261004 ")));
    }
}
