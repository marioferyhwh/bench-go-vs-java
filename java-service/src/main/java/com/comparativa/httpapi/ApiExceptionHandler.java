package com.comparativa.httpapi;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Da a los errores de transporte el mismo cuerpo JSON que define la spec.
 *
 * <p>No registra métricas: de eso se encarga MetricsFilter, que envuelve la
 * petición entera. Registrarlas también aquí contaba el 405 dos veces, y con
 * una latencia de cero que Go sí mide.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, String>> handleMethodNotAllowed() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Map.of("error", "method not allowed"));
    }
}
