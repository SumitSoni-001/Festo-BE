package com.festo.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

// base for all business exceptions; GlobalExceptionHandler maps these to ErrorResponse
@Getter
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    protected ApiException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }
}
