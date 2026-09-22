package com.pokeranalytics.api.dto;

import java.util.Map;

/** docs/10-api-design.md's error envelope: {@code { "error": { code, message, details } } }. */
public record ErrorResponse(ErrorBody error) {

    public record ErrorBody(String code, String message, Map<String, Object> details) {
    }

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(new ErrorBody(code, message, null));
    }
}
