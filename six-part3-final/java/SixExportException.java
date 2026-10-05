package com.bnpp.regliss.importer.six.service;

import org.springframework.dao.DataAccessResourceFailureException;
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

/**
 * Error of the SIX export, with two texts:
 *  - {@link #getMessage()}  : technical, for the SERVER LOG only (stage, list, file type, version, job, root cause);
 *  - {@link #mailText()}    : short, for the ALERT MAIL ("Error occurred during filtering of list 951 (instrument
 *                             file) due to database error. ...").
 */
public class SixExportException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private static final String STEP_WRITING_FILTERED = "writing the filtered data";
    private static final String STEP_GENERATING_XML = "generating the XML file";
    private static final String ERR_DATABASE_NOT_REACHABLE = "database not reachable";
    private static final String ERR_INVALID_FILTER = "invalid filter definition";
    private static final String ERR_DUPLICATE_REFERENCE = "duplicated external reference in the filtered data";
    private static final String ERR_NO_ACTIVE_LIST = "no active output list";
    private static final String ERR_XML_NOT_VALID = "XML file not valid against the schema";
    private static final String ERR_FILE_NOT_WRITTEN = "file could not be written to the DJ IN folder";
    private static final String ERR_INVALID_DATA = "invalid data in the SIX file";
    private static final String ERR_DATABASE_ERROR = "database error";
    private static final String ERR_UNEXPECTED = "unexpected technical error";

    /** Where the export stopped: technical text (log) and business step (mail). */
    public enum Stage {
        PREPARE("while preparing the export (output lists, filters, CMIC / E014071 exclusions)", "preparation of the export"),
        FILTER("while applying the filters of the list (SQL on the raw SIX tables)", "filtering"),
        DELETE_PREVIOUS("while deleting the previous filtered rows of the list (FILTERED_SIX_*)", STEP_WRITING_FILTERED),
        WRITE("while inserting the filtered rows into FILTERED_SIX_*", STEP_WRITING_FILTERED),
        ANNOUNCE("while marking the list as filtered (SIX_FILTERED_POLLER)", STEP_WRITING_FILTERED),
        READ_FILTERED("while reading the filtered rows of the list (FILTERED_SIX_*)", STEP_GENERATING_XML),
        GENERIC_RULES("while applying the generic rules (duplicated ISIN / sanctions)", STEP_GENERATING_XML),
        BUILD_XML("while building the XML records of the list (ListTypeBuilder)", STEP_GENERATING_XML),
        WRITE_FILE("while creating the CONVERTER file (file name, XML, XSD validation, move to the DJ IN folder)", STEP_GENERATING_XML);

        private final String logText;
        private final String step;

        Stage(String logText, String step) {
            this.logText = logText;
            this.step = step;
        }

        public String getLogText() {
            return logText;
        }

        public String getStep() {
            return step;
        }
    }

    private final Stage stage;
    private final String listRef;
    private final String fileType;
    private final Long jobId;

    /**
     * @param where    technical location for the log, e.g. "INSTRUMENT file version 101, delivery ..., job 5"
     * @param listRef  output list, null when the error concerns all lists
     * @param fileType "instrument" / "structured" / "options" (mail), null for the XML step (both file types)
     */
    public SixExportException(Stage stage, String where, String listRef, String fileType, Long jobId, Throwable cause) {
        super("SIX export STOPPED " + stage.getLogText() + " - " + (listRef == null ? "all lists" : "list " + listRef)
                + ", " + where + ". Cause: " + rootCause(cause), cause);
        this.stage = stage;
        this.listRef = listRef;
        this.fileType = fileType;
        this.jobId = jobId;
    }

    public Stage getStage() {
        return stage;
    }

    public String getListRef() {
        return listRef;
    }

    /** The error concerns every list (database not reachable, preparation): both servers stop. */
    public boolean stopsAllLists() {
        return stage == Stage.PREPARE || isConnectionError(getCause());
    }

    /** Short business text of the alert mail; the technical detail stays in the server log. */
    public String mailText() {
        String what = (listRef == null ? "all lists" : "list " + listRef) + (fileType == null ? "" : " (" + fileType + " file)");
        String next = stopsAllLists()
                ? "The export of this delivery is stopped on both servers."
                : "The other lists continue. Please regenerate this list.";
        return "Error occurred during " + stage.getStep() + " of " + what + " due to " + reason(getCause()) + ". " + next
                + " Technical details: server log" + (jobId == null ? "" : " of job " + jobId) + ".";
    }

    /** Business-level reason of a technical error (for the mail). */
    public static String reason(Throwable error) {
        if (isConnectionError(error)) {
            return ERR_DATABASE_NOT_REACHABLE;
        }
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String result = checkMessageBasedError(t);
            if (result != null) {
                return result;
            }
            result = checkExceptionTypeError(t);
            if (result != null) {
                return result;
            }
        }
        return ERR_UNEXPECTED;
    }

    private static String checkMessageBasedError(Throwable t) {
        String message = t.getMessage() == null ? "" : t.getMessage();
        if (message.startsWith("SIX filter")) {
            return ERR_INVALID_FILTER;
        }
        if (message.contains("Duplicate external reference")) {
            return ERR_DUPLICATE_REFERENCE;
        }
        if (message.startsWith("No active output list")) {
            return ERR_NO_ACTIVE_LIST;
        }
        return null;
    }

    private static String checkExceptionTypeError(Throwable t) {
        if (t instanceof SAXException) {
            return ERR_XML_NOT_VALID;
        }
        if (t instanceof IOException || t instanceof UncheckedIOException) {
            return ERR_FILE_NOT_WRITTEN;
        }
        if (t instanceof NumberFormatException || t instanceof DateTimeParseException) {
            return ERR_INVALID_DATA;
        }
        if (isDatabaseError(t)) {
            return ERR_DATABASE_ERROR;
        }
        return null;
    }

    private static boolean isDatabaseError(Throwable t) {
        String message = t.getMessage() == null ? "" : t.getMessage();
        return t instanceof SQLException || message.contains("ORA-")
                || t.getClass().getName().startsWith("org.springframework.dao.")
                || t.getClass().getName().startsWith("org.springframework.jdbc.");
    }

    /** Database not reachable (connection lost / refused, pool exhausted): no list can be processed. */
    public static boolean isConnectionError(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof CannotGetJdbcConnectionException || t instanceof CannotCreateTransactionException
                    || t instanceof DataAccessResourceFailureException || t instanceof SQLRecoverableException
                    || t instanceof SQLNonTransientConnectionException || t instanceof SQLTransientConnectionException) {
                return true;
            }
        }
        return false;
    }

    /** Deepest cause: the Oracle error (ORA-xxxxx) or the real exception, not the Spring / proxy wrappers. */
    public static String rootCause(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String text = root.getMessage() == null ? "" : root.getMessage().trim();
        return root.getClass().getSimpleName() + (text.isEmpty() ? "" : ": " + text);
    }
}
