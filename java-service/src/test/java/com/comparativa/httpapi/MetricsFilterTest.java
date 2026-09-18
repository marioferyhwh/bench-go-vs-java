package com.comparativa.httpapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Comprueba que el filtro mide lo mismo que el servicio Go: toda petición a
 * /data queda registrada con su status y su modo, incluidas las que nunca
 * llegan al controlador.
 */
class MetricsFilterTest {

    private MeterRegistry registry;
    private MetricsFilter filter;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        filter = new MetricsFilter(new DataMetrics(registry));
    }

    @Test
    void registraPeticionEnModoSimulacion() throws Exception {
        ejecutar("GET", "/data", null, 200);

        assertThat(contador("200", "simulation")).isEqualTo(1);
        assertThat(cronometro("200", "simulation")).isEqualTo(1);
    }

    @Test
    void useDatabaseTrueQuedaEtiquetadoComoDatabase() throws Exception {
        ejecutar("GET", "/data", "true", 200);

        assertThat(contador("200", "database")).isEqualTo(1);
    }

    /**
     * El 405 lo rechaza Spring antes del controlador. Sin el filtro esta
     * petición no se contaba en Java pero sí en Go.
     *
     * <p>El modo es "unknown" y no "simulation": Go comprueba el método antes
     * de resolver el modo, así que su 405 nunca lleva un modo concreto.
     */
    @Test
    void registraElMetodoNoPermitidoComoUnknown() throws Exception {
        ejecutar("POST", "/data", null, 405);

        assertThat(contador("405", "unknown")).isEqualTo(1);
    }

    /** Ni siquiera un useDatabase válido da modo a un método rechazado. */
    @Test
    void elMetodoNoPermitidoIgnoraElParametro() throws Exception {
        ejecutar("POST", "/data", "true", 405);

        assertThat(contador("405", "unknown")).isEqualTo(1);
        assertThat(registry.find("http_requests_total").tag("mode", "database").counters())
                .isEmpty();
    }

    /** Un valor inválido no es ni simulación ni base: se etiqueta unknown. */
    @Test
    void valorInvalidoQuedaEtiquetadoComoUnknown() throws Exception {
        ejecutar("GET", "/data", "yes", 400);

        assertThat(contador("400", "unknown")).isEqualTo(1);
    }

    /** Solo se mide /data: el endpoint de métricas queda fuera. */
    @Test
    void noMideOtrasRutas() throws Exception {
        ejecutar("GET", "/metrics", null, 200);

        assertThat(registry.find("http_requests_total").counters()).isEmpty();
    }

    private void ejecutar(String metodo, String ruta, String useDatabase, int status)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(metodo, ruta);
        request.setRequestURI(ruta);
        if (useDatabase != null) {
            request.setParameter("useDatabase", useDatabase);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(status);
        FilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);
    }

    private double contador(String status, String mode) {
        return registry.find("http_requests_total").tag("status", status).tag("mode", mode)
                .counter()
                .count();
    }

    private long cronometro(String status, String mode) {
        Timer timer = registry.find("http_request_duration_seconds")
                .tag("status", status)
                .tag("mode", mode)
                .timer();
        return timer == null ? 0 : timer.count();
    }
}
