package com.festo.common.response;

import java.util.Map;

// standard error shape for every endpoint: { "error", "message", "details" }
public record ErrorResponse(String error, String message, Map<String, Object> details) {

    public static ErrorResponse of(String error, String message) {
        return new ErrorResponse(error, message, Map.of());
    }

    public static ErrorResponse withDetails(String error, String message, Map<String, Object> details) {
        return new ErrorResponse(error, message, details);
    }
}
