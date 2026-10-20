package com.bnpp.regliss.importer.six.service;
// TODO: re-add project imports (Alt+Enter / Optimize Imports) for the project classes used below

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class SixImportExceptionTest {

    @Test
    public void messageOnly() {
        SixImportException e = new SixImportException("boom");
        assertEquals("boom", e.getMessage());
        assertNull(e.getCause());
    }

    @Test
    public void messageAndCause() {
        IllegalStateException cause = new IllegalStateException("root");
        SixImportException e = new SixImportException("boom", cause);
        assertEquals("boom", e.getMessage());
        assertSame(cause, e.getCause());
    }
}
