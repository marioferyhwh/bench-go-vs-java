package com.comparativa.httpapi;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Publica las mismas métricas que el servicio Go: http_requests_total,
 * http_errors_total y http_request_duration_seconds, etiquetadas por method,
 * route, status y mode.
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

    /** Valor del label route. El servicio solo mide /data, igual que Go. */
    public static final String ROUTE = "/data";

    /**
     * Métodos que se etiquetan tal cual. Cualquier otro sale como "OTHER": un
     * método arbitrario enviado por un cliente crearía una serie por valor.
     * La lista es la misma que knownMethods en Go.
     */
    private static final Set<String> KNOWN_METHODS =
            Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    private final MeterRegistry registry;

    public DataMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String method, int status, String mode, Duration elapsed) {
        String statusLabel = String.valueOf(status);
        String methodLabel = methodLabel(method);

        Counter.builder("http_requests_total")
                .description("Total de peticiones HTTP a /data.")
                .tag("method", methodLabel)
                .tag("route", ROUTE)
                .tag("status", statusLabel)
                .tag("mode", mode)
                .register(registry)
                .increment();

        // Contador aparte para status >= 400, como en Go y en el video de
        // referencia: el error rate sale de una serie pequeña y directa.
        if (status >= 400) {
            Counter.builder("http_errors_total")
                    .description("Total de respuestas de /data con status >= 400.")
                    .tag("method", methodLabel)
                    .tag("route", ROUTE)
                    .tag("status", statusLabel)
                    .tag("mode", mode)
                    .register(registry)
                    .increment();
        }

        Timer.builder("http_request_duration_seconds")
                .description("Latencia de /data en segundos.")
                .serviceLevelObjectives(bucketsAsDurations())
                .publishPercentileHistogram(false)
                .tag("method", methodLabel)
                .tag("route", ROUTE)
                .tag("status", statusLabel)
                .tag("mode", mode)
                .register(registry)
                .record(elapsed);
    }

    /** El método tal cual si es estándar, u "OTHER" si no. */
    static String methodLabel(String method) {
        return KNOWN_METHODS.contains(method) ? method : "OTHER";
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
