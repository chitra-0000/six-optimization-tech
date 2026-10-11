package com.bnpp.regliss.web.filter;

// TODO: imports (lines 3-22) were folded in the IDE; reconstructed from usage.
//       Verify the project-class packages and which QueryTimeoutException is imported.
import com.bnpp.regliss.ReglissException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.dao.QueryTimeoutException;   // TODO verify: could be javax.persistence.QueryTimeoutException
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Locale;
// TODO: add imports for ErrorCode, ErrorResponse, ErrorResponseWrapper,
//       ReglissEnvironment, ReglissRequestContext (packages not visible)

@ControllerAdvice
public class GlobalExceptionHandler {

    public static final String DEFAULT_ERROR_VIEW = "error";

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Autowired
    private MessageSource messageSource;

    @Autowired
    private ReglissRequestContext requestContext;

    @ResponseBody
    @ExceptionHandler(ReglissException.class)
    public ResponseEntity<ErrorResponseWrapper> handleBusinessException(HttpServletRequest request, ReglissException reglissException) {
        if (log.isErrorEnabled()) {
            String logMessage = "Converting business exception, code: " + reglissException.getErrorCode();
            if(reglissException.getParameters()!=null&&reglissException.getParameters().length>0){
                logMessage+=" params:"+ Arrays.toString(reglissException.getParameters());
            }
            log.error(logMessage, reglissException);
        }
        String messageKey = "error." + reglissException.getErrorCode();
        String userMessage = messageSource.getMessage(messageKey, reglissException.getParameters(), requestContext.getLocale());

        HttpStatus httpStatus = getHttpStatus(reglissException.getErrorCode());

        ErrorResponse response = new ErrorResponse(httpStatus.value());
        response.setRequestId(requestContext.getRequestId());
        response.setUserMessage(userMessage);
        response.setErrorCode(reglissException.getErrorCode().name());
        return ResponseEntity
                .status(httpStatus)
                .body(new ErrorResponseWrapper(response));
    }

    private HttpStatus getHttpStatus(ErrorCode errorCode) {
        switch(errorCode) {
        case NOT_FOUND: return HttpStatus.NOT_FOUND;
        case NOT_AUTHORIZED: return HttpStatus.PRECONDITION_FAILED;
        case AUTHENTICATION_HIGH_LEVEL_REQUIRED: return HttpStatus.PRECONDITION_FAILED;
        case NO_PERMISSION_FOR_LIST: return HttpStatus.PRECONDITION_FAILED;
        case NOT_ALLOWED_BY_PROFILE: return HttpStatus.PRECONDITION_FAILED;
        default: return HttpStatus.BAD_REQUEST;
        }
    }

    @ResponseBody
    @ExceptionHandler(value = QueryTimeoutException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponseWrapper timeoutErrorHandler(HttpServletRequest request, QueryTimeoutException exception) throws Exception {
        return handleException(exception, ErrorCode.QUERY_TIMEOUT);
    }

    @ResponseBody
    @ExceptionHandler(value = Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponseWrapper defaultErrorHandler(HttpServletRequest request, Exception exception) throws Exception {
        return handleException(exception, ErrorCode.GENERAL);
    }

    private ErrorResponseWrapper handleException(Exception exception, ErrorCode errorCode) {
        if (log.isErrorEnabled()) {
            String logMessage = "Converting unexpected exception: " + exception;
            log.error(logMessage, exception);
        }
        String messageKey = "error." + errorCode;
        Locale errorLocale = requestContext.getLocale() != null ? requestContext.getLocale() : Locale.ENGLISH;
        String userMessage = messageSource.getMessage(messageKey, new Object[0], errorLocale);
        if (ReglissEnvironment.isLocalDev() &&  StringUtils.isNotEmpty(exception.getMessage())) {
            userMessage = exception.toString();
        }

        ErrorResponse response = new ErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR.value());
        response.setRequestId(requestContext.getRequestId());
        response.setUserMessage(userMessage);
        response.setErrorCode(errorCode.name());

        return new ErrorResponseWrapper(response);
    }

}
