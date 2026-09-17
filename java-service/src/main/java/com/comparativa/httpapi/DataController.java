package com.comparativa.httpapi;

import com.comparativa.data.DataResult;
import com.comparativa.data.DataService;
import com.comparativa.data.DatabaseUnavailableException;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Traduce peticiones HTTP a llamadas del servicio de datos. No contiene lógica
 * de negocio: solo lee el query param, mide y serializa la respuesta.
 */
@RestController
public class DataController {

    private final DataService service;
    private final DataMetrics metrics;

    public DataController(DataService service, DataMetrics metrics) {
        this.service = service;
        this.metrics = metrics;
    }

    @GetMapping(path = "/data", produces = MediaType.APPLICATION_JSON_VALUE)
    public DataResult getData(@RequestParam(name = "useDatabase", required = false) String useDatabase) {
        long start = System.nanoTime();
        boolean toDatabase = parseUseDatabase(useDatabase, start);
        String mode = toDatabase ? "database" : "simulation";

        try {
            DataResult result = service.get(toDatabase);
            metrics.record(HttpStatus.OK.value(), mode, elapsedSince(start));
            return result;
        } catch (DatabaseUnavailableException e) {
            metrics.record(HttpStatus.SERVICE_UNAVAILABLE.value(), mode, elapsedSince(start));
            throw e;
        }
    }

    /**
     * Acepta solo "true", "false" o ausente. El binding automático de Spring
     * aceptaría además "on", "yes" o "1", que la spec rechaza.
     */
    private boolean parseUseDatabase(String raw, long start) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        if (!"true".equals(raw) && !"false".equals(raw)) {
            metrics.record(HttpStatus.BAD_REQUEST.value(), "unknown", elapsedSince(start));
            throw new InvalidUseDatabaseException();
        }
        return "true".equals(raw);
    }

    private static Duration elapsedSince(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    @ExceptionHandler(InvalidUseDatabaseException.class)
    ResponseEntity<Map<String, String>> handleInvalidUseDatabase() {
        return ResponseEntity.badRequest().body(Map.of("error", "invalid useDatabase value"));
    }

    @ExceptionHandler(DatabaseUnavailableException.class)
    ResponseEntity<Map<String, String>> handleDatabaseUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "database unavailable"));
    }

    static class InvalidUseDatabaseException extends RuntimeException {
    }
}
