package com.comparativa.httpapi;

import com.comparativa.data.DataResult;
import com.comparativa.data.DataService;
import com.comparativa.data.DatabaseUnavailableException;
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
 * de negocio: solo lee el query param y serializa la respuesta.
 *
 * <p>Las métricas no se registran aquí sino en MetricsFilter, que envuelve el
 * request completo para medir el mismo alcance que el servicio Go.
 */
@RestController
public class DataController {

    private final DataService service;

    public DataController(DataService service) {
        this.service = service;
    }

    @GetMapping(path = "/data", produces = MediaType.APPLICATION_JSON_VALUE)
    public DataResult getData(@RequestParam(name = "useDatabase", required = false) String useDatabase) {
        return service.get(parseUseDatabase(useDatabase));
    }

    /**
     * Acepta solo "true", "false" o ausente. El binding automático de Spring
     * aceptaría además "on", "yes" o "1", que la spec rechaza.
     */
    private static boolean parseUseDatabase(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        if (!"true".equals(raw) && !"false".equals(raw)) {
            throw new InvalidUseDatabaseException();
        }
        return "true".equals(raw);
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
