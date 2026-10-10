package com.bnpp.regliss;

import java.util.Arrays;

/**
 * A business error condition that can be translated to be displayed to the end user.
 */
@SuppressWarnings("serial")   // TODO: shown folded as "/serial/" in IntelliJ - check the exact line in the IDE
public class ReglissException extends RuntimeException {

    // NOTE: lines 1-39 and 117-133 were read from BLURRED photos - check the enum names against the IDE
    public enum ErrorCode {
        GENERAL,
        QUERY_TIMEOUT,
        LIST_REFERENCE_ALREADY_IN_USE,
        LIST_NAME_ALREADY_IN_USE,
        LIST_SHORTNAME_ALREADY_IN_USE,
        LIST_WITH_IMPORT_FILE_TYPE_ALREADY_EXISTS,
        LIST_AUTOMATIC_AND_NON_CORE_CANNOT_BE_DEAC,
        LIST_CANNOT_DEACTIVATE_VERSIONS_ONGOING,
        RECORD_EXTERNAL_REFERENCE_ALREADY_IN_USE,
        RECORD_RECORD_ID_LIMIT_EXCEEDED,
        FILE_NOT_IN_REQUEST,
        FILE_PROCESS_ERROR,
        SEARCH_MULTIPLE_WILDCARDS,

        FILTER_TAG_ALREADY_IN_USE,
        FILTER_TAG_IS_REQUIRED,

        CREATE_VERSION_ALREADY_EXISTS,
        EMPTY_VERSION_CANNOT_BE_CERTIFIED,

        MAPPING_FILE_NOT_DEFINED,
        MAPPING_FILE_PARSE_ERROR,
        MAPPING_FILE_ILLEGAL_EXTENSION,
        MAPPING_FILE_EMPTY,
        MAPPING_FILE_DUPLICATE_FIELDS,


        IMPORT_FILE_PARSE_ERROR,
        IMPORT_FILE_HEADER_DIFFERENT,
        IMPORT_FILE_HEADER_LENGTH_DIFFERENT_FROM_MAPPING_FILE_LENGTH,
        IMPORT_FILE_ILLEGAL_EXTENSION,
        MANUAL_IMPORT_ALREADY_STARTED_FOR_VERSION,

        IMPORT_ILLEGAL_VALUE,
        IMPORT_DUPLICATE_EXTERNAL_REFERENCE,
        IMPORT_ROW_LENGTH_DIFFERENT_FROM_HEADER_LENGTH,
        IMPORT_RECORD_MISSING_TYPE,
        IMPORT_RECORD_MISSING_NAME,
        IMPORT_RECORD_MISSING_EXTERNAL_REFERENCE,
        IMPORT_RECORD_MISSING_START_DATE,
        IMPORT_RECORD_INVALID_START_DATE_FORMAT,
        IMPORT_RECORD_INVALID_DATE_DETAILS_VALUE_FORMAT,
        IMPORT_RECORD_INVALID_DEATH_DATE_FORMAT,
        IMPORT_RECORD_INVALID_FUNCTION_FROM_DATE,
        IMPORT_RECORD_INVALID_FUNCTION_TO_DATE,
        IMPORT_RECORD_MISSING_SANCTION_TYPE,
        IMPORT_RECORD_MISSING_NOTES,
        IMPORT_INVALID_DOCUMENT_ISSUING_DATE_FORMAT,
        IMPORT_INVALID_DOCUMENT_EXPIRATION_DATE_FORMAT,
        IMPORT_INVALID_SANCTION_REFERENCE_SINCE_DATE,
        IMPORT_INVALID_SANCTION_REFERENCE_END_DATE,
        IMPORT_ADDRESS_WITHOUT_CITY_COUNTRY,
        IMPORT_ADDRESS_STREET_WITHOUT_CITY,
        IMPORT_ALIAS_WITHOUT_REFERENCE,
        IMPORT_ALIAS_WITHOUT_NAME,
        IMPORT_PROGRAM_WITHOUT_LABEL,
        IMPORT_DOCUMENT_WITHOUT_TYPE,
        IMPORT_DOCUMENT_WITHOUT_NUMBER,
        IMPORT_PASSPORT_WITH_INVALID_NUMBER,
        IMPORT_BIOGRAPHY_EMPTY,
        IMPORT_NATIONALITY_WITHOUT_COUNTRY,
        IMPORT_RECORD_WITHOUT_PROGRAMS,
        IMPORT_PERSON_MISSING_LAST_NAME,
        IMPORT_PERSON_RECORD_NAME_EXISTS,
        IMPORT_PERSON_FULL_NAME_MIN_LENGTH,
        IMPORT_MANUAL_EMPTY_FILE,

        GENERATE_FILE_ERROR,
        SUBSCRIPTION_DUPLICATE_CFT_FLUX_NAME,

        NOT_FOUND,

        SEARCH_REFOG_USER_NOT_FOUND,
        CANNOT_CONNECT_TO_REFOG,
        CREATE_USER_UID_ALREADY_USED,
        CREATE_USER_REFOG_COUNTRY_NOT_IN_REGLISS,
        SUPPORT_MOA_CANNOT_EDIT_OWN_PROFILE,
        CANNOT_EDIT_PROFILE,
        LOST_LOCK,
        LOST_LOCK_FOR_SAVE_VERSION,
        SYS_VERSION_LOCK_CANNOT_BE_OVERWRITTEN,

        NO_PERMISSION_FOR_LIST,
        AUTHENTICATION_HIGH_LEVEL_REQUIRED,

        DJ_IMPORT_VALIDATION_ERROR,
        NOT_ALLOWED_BY_PROFILE,
        DJ_WAIT_FOR_ADDITIONAL_FILE,

        DJ_IMPORT_CHECKSUM_INCORRECT,
        DJ_IMPORT_XSD_VALIDATION_FAILED,
        DJ_IMPORT_ZIP_CORRUPT,
        DJ_IMPORT_LOG_COUNT_INCORRECT,
        DJ_IMPORT_FUTURE_DATE,
        CA_APPLICATION_VERSION_TOO_OLD,
        AUTOMATIC_VERSION_IN_PROGRESS,
        CA_IMPORT_VERSION_TOO_OLD,

        NOT_AUTHORIZED,

        ADD_MOD_SUP_NOT_ELIGIBLE_FOR_VERSION,
        ADD_MOD_SUP_ALREADY_GENERATED,
        MIN_AMOUNT_BETWEEN_REGENERATION,
        ADD_MOD_SUP_NOT_GENERATED,

        WRONG_ENCODING_FILE,

        ERROR_CONSTRAINT_VIOLATION,
        ERROR_CONSTRAINT_THRESHOLD_VIOLATION,

        VERSION_NONEXISTENT_FOR_REGLEMENTATION,
        NO_LIST_ID_PROVIDED_FOR_REGLEMENTATION,
        LIST_ID_NOT_ASSOCIATED_FOR_VERSION_REGLEMENTATION,
        CA_EMPTY_FILE,
        COMPARE_VERSIONS_SAME_VERSION,
        COMPARE_VERSIONS_SELECT_VALID_CRITERIA,
        COMPARE_VERSIONS_SELECT_DIFFERENT_VERSIONS,
        COMPARE_VERSIONS_SELECT_DIFFERENT_VERSIONS_OR_DATES,
        FILE_TYPE_INVALID,
        FILE_NAME_DOES_NOT_MATCH_REGEX,
        FILE_SIZE_EXCEEDS,
        FILE_EXTENSION_INVALID
    }

    private final ErrorCode errorCode;
    private final String[] parameters;

    public ReglissException() {
        this((String)null);
    }

    public ReglissException(String message) {
        this(message, null, ErrorCode.GENERAL);
    }

    public ReglissException(Throwable cause) {
        this(cause.toString(), cause, ErrorCode.GENERAL);
    }

    public ReglissException(Throwable cause, String message) {
        this(message, cause, ErrorCode.GENERAL);
    }

    public ReglissException(ErrorCode errorCode, String... parameters) {
        this(null, null, errorCode, parameters);
    }

    public ReglissException(String exceptionMessage, ErrorCode errorCode, String... parameters) {
        this(exceptionMessage, null, errorCode, parameters);
    }

    public ReglissException(Throwable cause, ErrorCode errorCode, String... parameters) {
        this(null, cause, errorCode, parameters);
    }

    public ReglissException(String exceptionMessage, Throwable cause, ErrorCode errorCode, String... parameters) {
        super((exceptionMessage != null ? exceptionMessage : "") + (errorCode!=ErrorCode.GENERAL?errorCode.name():"") + (parameters.length>0?" " + Arrays.toString(parameters):""), cause);
        this.errorCode = errorCode;
        this.parameters = parameters;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public String[] getParameters() {
        return parameters;
    }
}
