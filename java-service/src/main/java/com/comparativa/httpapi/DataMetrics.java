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

    /**
     * Cubos del histograma de latencia, en segundos. La lista es idéntica a
     * LatencyBuckets del servicio Go y no debe cambiar en una sola de las dos:
     * histogram_quantile interpola dentro del cubo donde cae el percentil, así
     * que cubos distintos producen percentiles con precisión distinta y la
     * comparación deja de medir las tecnologías para medir la instrumentación.
     *
     * <p>Es densa entre 1 y 100 ms, que es donde viven estas latencias, y
     * mantiene una cola hasta 10 s para los arranques en frío de la JVM.
     *
     * <p>Se fijan a mano en lugar de usar publishPercentileHistogram(), que
     * genera unos 70 cubos propios de Micrometer sin equivalente en Go.
     */
    private static final double[] LATENCY_BUCKETS_SECONDS = {
        0.001, 0.002, 0.003, 0.005, 0.0075, 0.010, 0.015, 0.025,
        0.050, 0.075, 0.100, 0.250, 0.500, 1.0, 2.5, 10.0
    };

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
                .serviceLevelObjectives(bucketsAsDurations())
                .publishPercentileHistogram(false)
                .tag("status", statusLabel)
                .tag("mode", mode)
                .register(registry)
                .record(elapsed);
    }

    /**
     * Los cubos como Duration, que es lo que espera Micrometer. Se redondean a
     * nanosegundos: 1 ms son 1.000.000 ns exactos, así que ninguno de los
     * valores de la lista pierde precisión en la conversión.
     */
    private static Duration[] bucketsAsDurations() {
        Duration[] cubos = new Duration[LATENCY_BUCKETS_SECONDS.length];
        for (int i = 0; i < LATENCY_BUCKETS_SECONDS.length; i++) {
            cubos[i] = Duration.ofNanos(Math.round(LATENCY_BUCKETS_SECONDS[i] * 1_000_000_000L));
        }
        return cubos;
    }
}
