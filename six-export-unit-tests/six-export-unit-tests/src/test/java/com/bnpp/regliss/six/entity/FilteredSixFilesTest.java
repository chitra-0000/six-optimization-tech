package com.bnpp.regliss.six.entity;

import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

// TODO: re-add project imports for: FilteredSixTarget

public class FilteredSixFilesTest {

    @Test
    public void instrument_accessors_equalsById_addTarget() {
        FilteredInstrumentFile a = new FilteredInstrumentFile();
        assertTrue(BeanProperties.checkAll(a) > 10);
        a.setId(1L);
        FilteredInstrumentFile b = new FilteredInstrumentFile();
        b.setId(1L);
        FilteredInstrumentFile c = new FilteredInstrumentFile();
        c.setId(2L);
        assertEquals(a, a);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotEquals(a, "x");

        FilteredSixTarget target = mock(FilteredSixTarget.class);
        a.setSixTargets(new ArrayList<>());
        a.addSixTarget(target);
        assertSame(target, a.getSixTargets().get(0));
        verify(target).setInstrumentFile(a);
    }

    @Test
    public void structured_accessors_equalsById_addTarget() {
        FilteredStructuredFile a = new FilteredStructuredFile();
        assertTrue(BeanProperties.checkAll(a) > 10);
        a.setId(1L);
        FilteredStructuredFile b = new FilteredStructuredFile();
        b.setId(1L);
        FilteredStructuredFile c = new FilteredStructuredFile();
        c.setId(2L);
        assertEquals(a, a);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotEquals(a, "x");

        FilteredSixTarget target = mock(FilteredSixTarget.class);
        a.setSixTargets(new ArrayList<>());
        a.addSixTarget(target);
        assertSame(target, a.getSixTargets().get(0));
        verify(target).setStructuredFile(a);

        a.removeSixTarget(target);
        assertTrue(a.getSixTargets().isEmpty());
        verify(target).setStructuredFile(null);
    }
}
