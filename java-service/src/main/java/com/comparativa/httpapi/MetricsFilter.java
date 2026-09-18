package com.comparativa.httpapi;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Mide cada petición a /data desde fuera del controlador, envolviendo el
 * request completo.
 *
 * <p>La medición vive aquí y no en DataController para igualar el alcance con
 * el servicio Go, que arranca el cronómetro en la primera línea de ServeHTTP.
 * Midiendo dentro del método del controlador quedaban fuera el enrutado, el
 * binding de parámetros y el resto de filtros de Spring: Java salía
 * sistemáticamente más rápido que Go por medir una ventana más corta, no por
 * ser más rápido.
 *
 * <p>Por la misma razón el filtro registra todas las respuestas, incluidas las
 * que nunca llegan al controlador. El 405 de un POST lo rechaza Spring antes,
 * así que sin esto Java no contaba una petición que Go sí contaba.
 */
@Component
@Order(Integer.MIN_VALUE)
public class MetricsFilter extends OncePerRequestFilter {

    private static final String RUTA_MEDIDA = "/data";

    private final DataMetrics metrics;

    public MetricsFilter(DataMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * Solo se mide la petición original a /data. Quedan fuera /metrics y el
     * resto de rutas, y también los despachos internos que Spring hace sobre
     * la misma petición al resolver un error: el 405 vuelve a pasar por aquí
     * como FORWARD hacia /error, y sin descartarlo se contaría dos veces
     * mientras Go lo cuenta una.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getDispatcherType() != DispatcherType.REQUEST
                || !RUTA_MEDIDA.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long inicio = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            metrics.record(
                    response.getStatus(),
                    modoDe(request),
                    Duration.ofNanos(System.nanoTime() - inicio));
        }
    }

    /**
     * El modo se deduce del query param, no del controlador: una petición
     * rechazada antes de llegar a él igual tiene que quedar etiquetada.
     *
     * <p>Un método que no es GET se etiqueta "unknown" aunque traiga un
     * useDatabase válido: Go resuelve el modo después de comprobar el método,
     * así que su 405 sale siempre como "unknown" y aquí tiene que salir igual.
     */
    private static String modoDe(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())) {
            return "unknown";
        }
        String raw = request.getParameter("useDatabase");
        if (raw == null || raw.isEmpty()) {
            return "simulation";
        }
        if ("true".equals(raw)) {
            return "database";
        }
        if ("false".equals(raw)) {
            return "simulation";
        }
        return "unknown";
    }
}
