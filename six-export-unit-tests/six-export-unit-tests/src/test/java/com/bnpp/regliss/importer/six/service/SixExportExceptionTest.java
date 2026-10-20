package com.bnpp.regliss.importer.six.service;

// TODO: re-add project imports (Alt+Enter / Optimize Imports) for the project classes used below

import org.junit.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.time.format.DateTimeParseException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SixExportExceptionTest {

    /** An exception that is its own cause (some drivers do this): the loops must stop. */
    private static final class SelfCaused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SelfCaused(String message) {
            super(message);
        }

        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }

    @Test
    public void stageTexts() {
        for (SixExportException.Stage stage : SixExportException.Stage.values()) {
            assertFalse(stage.getLogText().isEmpty());
            assertFalse(stage.getStep().isEmpty());
        }
        assertEquals("filtering", SixExportException.Stage.FILTER.getStep());
        assertSame(SixExportException.Stage.WRITE, SixExportException.Stage.valueOf("WRITE"));
    }

    @Test
    public void listError_messageAndMail() {
        SixExportException e = new SixExportException(SixExportException.Stage.FILTER, "INSTRUMENT file version 101",
                "951", "instrument", 5L, new SQLException("ORA-00942: table or view does not exist"));
        assertEquals("SIX export STOPPED " + SixExportException.Stage.FILTER.getLogText()
                + " - list 951, INSTRUMENT file version 101. Cause: SQLException: ORA-00942: table or view does not exist", e.getMessage());
        assertSame(SixExportException.Stage.FILTER, e.getStage());
        assertEquals("951", e.getListRef());
        assertFalse(e.stopsAllLists());
        assertEquals("Error occurred during filtering of list 951 (instrument file) due to database error."
                + " The other lists continue. Please regenerate this list. Technical details: server log of job 5.", e.mailText());
    }

    @Test
    public void allListsError_prepareStage_noJob_noFileType() {
        SixExportException e = new SixExportException(SixExportException.Stage.PREPARE, "delivery 1", null, null, null,
                new IllegalStateException("No active output list for 951"));
        assertTrue(e.getMessage().contains(" - all lists, delivery 1."));
        assertTrue(e.stopsAllLists());
        assertEquals("Error occurred during preparation of the export of all lists due to no active output list."
                + " The export of this delivery is stopped on both servers. Technical details: server log.", e.mailText());
    }

    @Test
    public void connectionError_stopsAllLists() {
        SixExportException e = new SixExportException(SixExportException.Stage.WRITE, "x", "235", "structured", 1L,
                new IllegalStateException("wrapper", new CannotGetJdbcConnectionException("pool")));
        assertTrue(e.stopsAllLists());
        assertTrue(e.mailText().contains("due to database not reachable."));
    }

    @Test
    public void reason_byMessage() {
        assertEquals("invalid filter definition", SixExportException.reason(new IllegalArgumentException("SIX filter 3 has no field")));
        assertEquals("duplicated external reference in the filtered data",
                SixExportException.reason(new IllegalStateException("x Duplicate external reference y")));
        assertEquals("no active output list", SixExportException.reason(new IllegalStateException("No active output list")));
    }

    @Test
    public void reason_byType() {
        assertEquals(SixExportException.NOT_ENOUGH_MEMORY, SixExportException.reason(new OutOfMemoryError()));
        assertEquals("XML file not valid against the schema", SixExportException.reason(new SAXException("bad")));
        assertEquals("file could not be written to the holding or DJ IN folder", SixExportException.reason(new IOException("disk")));
        assertEquals("file could not be written to the holding or DJ IN folder",
                SixExportException.reason(new UncheckedIOException(new IOException("disk"))));
        assertEquals("invalid data in the SIX file", SixExportException.reason(new NumberFormatException("x")));
        assertEquals("invalid data in the SIX file", SixExportException.reason(new DateTimeParseException("bad", "x", 0)));
        assertEquals("database error", SixExportException.reason(new SQLException("x")));
        assertEquals("database error", SixExportException.reason(new IllegalStateException("ORA-00001 unique")));
        assertEquals("database error", SixExportException.reason(new DataIntegrityViolationException("dup")));
        assertEquals("database error", SixExportException.reason(new BadSqlGrammarException("t", "s", new SQLException())));
    }

    @Test
    public void reason_fromCauseChain_orUnexpected() {
        assertEquals("database error", SixExportException.reason(new IllegalStateException("outer", new SQLException("inner"))));
        assertEquals(SixExportException.UNEXPECTED, SixExportException.reason(new IllegalStateException()));
        assertEquals(SixExportException.UNEXPECTED, SixExportException.reason(new SelfCaused("loop")));
        assertEquals(SixExportException.UNEXPECTED, SixExportException.reason(null));
    }

    @Test
    public void isConnectionError_everyType() {
        assertTrue(SixExportException.isConnectionError(new CannotGetJdbcConnectionException("x")));
        assertTrue(SixExportException.isConnectionError(new CannotCreateTransactionException("x")));
        assertTrue(SixExportException.isConnectionError(new DataAccessResourceFailureException("x")));
        assertTrue(SixExportException.isConnectionError(new SQLRecoverableException("x")));
        assertTrue(SixExportException.isConnectionError(new SQLNonTransientConnectionException("x")));
        assertTrue(SixExportException.isConnectionError(new SQLTransientConnectionException("x")));
        assertFalse(SixExportException.isConnectionError(new SQLException("x")));
        assertFalse(SixExportException.isConnectionError(new SelfCaused("loop")));
        assertFalse(SixExportException.isConnectionError(null));
    }

    @Test
    public void rootCause() {
        assertEquals("unknown", SixExportException.rootCause(null));
        assertEquals("SQLException: ORA-1", SixExportException.rootCause(new IllegalStateException("a", new SQLException(" ORA-1 "))));
        assertEquals("IllegalStateException", SixExportException.rootCause(new IllegalStateException("  ")));
        assertEquals("IllegalStateException", SixExportException.rootCause(new IllegalStateException((String) null)));
        assertEquals("SelfCaused: loop", SixExportException.rootCause(new SelfCaused("loop")));
        assertNull(new SixExportException(SixExportException.Stage.WRITE, "w", null, null, null, null).getCause());
    }
}
