package com.comparativa.httpapi;

import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Da a los errores de transporte el mismo cuerpo JSON que define la spec.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private final DataMetrics metrics;

    public ApiExceptionHandler(DataMetrics metrics) {
        this.metrics = metrics;
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, String>> handleMethodNotAllowed() {
        metrics.record(HttpStatus.METHOD_NOT_ALLOWED.value(), "unknown", Duration.ZERO);
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Map.of("error", "method not allowed"));
    }
}
