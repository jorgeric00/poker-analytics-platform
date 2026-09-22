package com.pokeranalytics.api;

import org.springframework.http.HttpStatus;

/**
 * A deliberate, client-facing API error — docs/10-api-design.md's error envelope. Thrown for
 * anything the caller could reasonably fix (bad input, an unsupported scope, a missing file);
 * {@link GlobalExceptionHandler} turns it into the documented {@code {"error": {code, message}}}
 * body. Anything else (a real bug, an unexpected DB error) falls through to that handler's
 * generic 500 case instead.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
