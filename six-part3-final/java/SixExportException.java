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

    /** Where the export stopped: technical text (log) and business step (mail). */
    public enum Stage {
        PREPARE("while preparing the export (output lists, filters, CMIC / E014071 exclusions)", "preparation of the export"),
        FILTER("while applying the filters of the list (SQL on the raw SIX tables)", "filtering"),
        DELETE_PREVIOUS("while deleting the previous filtered rows of the list (FILTERED_SIX_*)", "writing the filtered data"),
        WRITE("while inserting the filtered rows into FILTERED_SIX_*", "writing the filtered data"),
        ANNOUNCE("while marking the list as filtered (SIX_FILTERED_POLLER)", "writing the filtered data"),
        READ_FILTERED("while reading the filtered rows of the list (FILTERED_SIX_*)", "generating the XML file"),
        GENERIC_RULES("while applying the generic rules (duplicated ISIN / sanctions)", "generating the XML file"),
        BUILD_XML("while building the XML records of the list (ListTypeBuilder)", "generating the XML file"),
        WRITE_FILE("while creating the CONVERTER file (file name, XML, XSD validation, move to the DJ IN folder)", "generating the XML file");

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
            return "database not reachable";
        }
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String message = t.getMessage() == null ? "" : t.getMessage();
            if (message.startsWith("SIX filter")) {
                return "invalid filter definition";
            }
            if (message.contains("Duplicate external reference")) {
                return "duplicated external reference in the filtered data";
            }
            if (message.startsWith("No active output list")) {
                return "no active output list";
            }
            if (t instanceof SAXException) {
                return "XML file not valid against the schema";
            }
            if (t instanceof IOException || t instanceof UncheckedIOException) {
                return "file could not be written to the DJ IN folder";
            }
            if (t instanceof NumberFormatException || t instanceof DateTimeParseException) {
                return "invalid data in the SIX file";
            }
            if (t instanceof SQLException || message.contains("ORA-") || t.getClass().getName().startsWith("org.springframework.dao.")
                    || t.getClass().getName().startsWith("org.springframework.jdbc.")) {
                return "database error";
            }
        }
        return "unexpected technical error";
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
