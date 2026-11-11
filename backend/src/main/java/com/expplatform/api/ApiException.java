package com.expplatform.api;

import org.springframework.http.HttpStatus;

/** Business exception carrying an HTTP status (exception paths are surfaced to the UI). */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " not found");
    }
    public static ApiException conflict(String code, String msg) {
        return new ApiException(HttpStatus.CONFLICT, code, msg);
    }
    public static ApiException invalid(String code, String msg) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, msg);
    }
}
