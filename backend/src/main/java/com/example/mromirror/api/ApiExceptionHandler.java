package com.example.mromirror.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> api(ApiException e) {
        return error(e.status(), e.getMessage());
    }

    /** A single-row lookup that found nothing. */
    @ExceptionHandler(EmptyResultDataAccessException.class)
    public ResponseEntity<Map<String, String>> noRows(EmptyResultDataAccessException e) {
        return error(HttpStatus.NOT_FOUND, "not found");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> internal(Exception e) {
        // Spring MVC's own errors (unknown route, wrong method and so on) keep their status.
        if (e instanceof ErrorResponse er) {
            HttpStatus known = HttpStatus.resolve(er.getStatusCode().value());
            return error(er.getStatusCode(), known != null ? known.getReasonPhrase().toLowerCase() : "error");
        }
        log.error("error: {}", e.toString(), e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal error");
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatusCode status, String msg) {
        return ResponseEntity.status(status).body(Map.of("error", msg));
    }
}
