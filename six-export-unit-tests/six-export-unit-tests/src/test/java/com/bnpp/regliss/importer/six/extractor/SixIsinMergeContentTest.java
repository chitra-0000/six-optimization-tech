package com.bnpp.regliss.importer.six.extractor;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

// TODO: re-add project imports for: FilteredInstrumentFile, FilteredStructuredFile

public class SixIsinMergeContentTest {

    @Test
    public void none_isEmpty() {
        SixIsinMergeContent none = SixIsinMergeContent.none();
        assertSame(none, SixIsinMergeContent.none());
        assertFalse(none.hasActiveInstrument("X"));
        assertTrue(none.instrumentRows("X").isEmpty());
        assertTrue(none.structuredRows("X").isEmpty());
        assertEquals(0, none.activeInstrumentCount());
        assertEquals(0, none.mergedIsinCount());
    }

    @Test
    public void nullArguments_treatedAsEmpty() {
        SixIsinMergeContent content = new SixIsinMergeContent(null, null, null);
        assertEquals(0, content.activeInstrumentCount());
        assertEquals(0, content.mergedIsinCount());
    }

    @Test
    public void lookupsByIsin() {
        FilteredInstrumentFile i1 = mock(FilteredInstrumentFile.class);
        FilteredStructuredFile s1 = mock(FilteredStructuredFile.class);
        Map<String, List<FilteredInstrumentFile>> instruments = new HashMap<>();
        instruments.put("A", Collections.singletonList(i1));
        instruments.put("B", Collections.singletonList(i1));
        Map<String, List<FilteredStructuredFile>> structured = new HashMap<>();
        structured.put("B", Collections.singletonList(s1));
        structured.put("C", Collections.singletonList(s1));
        SixIsinMergeContent content = new SixIsinMergeContent(new HashSet<>(Arrays.asList("A", "Z")), instruments, structured);

        assertTrue(content.hasActiveInstrument("A"));
        assertFalse(content.hasActiveInstrument("B"));
        assertFalse(content.hasActiveInstrument(null));
        assertEquals(Collections.singletonList(i1), content.instrumentRows("A"));
        assertTrue(content.instrumentRows("C").isEmpty());
        assertTrue(content.instrumentRows(null).isEmpty());
        assertEquals(Collections.singletonList(s1), content.structuredRows("C"));
        assertTrue(content.structuredRows("A").isEmpty());
        assertTrue(content.structuredRows(null).isEmpty());
        assertEquals(2, content.activeInstrumentCount());
        assertEquals(3, content.mergedIsinCount());                     // A, B, C
    }

    @Test
    public void rowsAreReadOnly() {
        Map<String, List<FilteredInstrumentFile>> instruments = new HashMap<>();
        instruments.put("A", new ArrayList<>(Collections.singletonList(mock(FilteredInstrumentFile.class))));
        List<FilteredInstrumentFile> rows = new SixIsinMergeContent(null, instruments, null).instrumentRows("A");
        try {
            rows.clear();
            fail();
        } catch (UnsupportedOperationException e) {
            assertEquals(1, rows.size());
        }
    }
}
