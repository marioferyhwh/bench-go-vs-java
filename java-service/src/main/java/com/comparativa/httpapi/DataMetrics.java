package com.comparativa.httpapi;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Publica las mismas métricas que el servicio Go: http_requests_total y
 * http_request_duration_seconds, etiquetadas por status y mode.
 */
@Component
public class DataMetrics {

    private final MeterRegistry registry;

    public DataMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(int status, String mode, Duration elapsed) {
        String statusLabel = String.valueOf(status);

        Counter.builder("http_requests_total")
                .description("Total de peticiones HTTP a /data.")
                .tag("status", statusLabel)
                .tag("mode", mode)
                .register(registry)
                .increment();

        Timer.builder("http_request_duration_seconds")
                .description("Latencia de /data en segundos.")
                .publishPercentileHistogram()
                .tag("status", statusLabel)
                .tag("mode", mode)
                .register(registry)
                .record(elapsed);
    }
}
