package com.bnpp.regliss.importer.six.service;

/**
 * Dedicated exception of the SIX import (instead of a generic RuntimeException).
 * Unchecked, so it travels through the existing callers unchanged; the import's
 * error handling (AutomaticSixImportXmlService, AutomaticFeedImporter) catches it as before.
 */
public class SixImportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SixImportException(String message) {
        super(message);
    }

    public SixImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
