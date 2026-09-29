package com.comparativa.httpapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class StartupMetricsTest {

    @Test
    void valeCeroHastaQueLaAplicacionEstaLista() {
        MeterRegistry registry = new SimpleMeterRegistry();
        StartupMetrics metrics = new StartupMetrics(registry);

        assertThat(registry.get("app_startup_duration_seconds").gauge().value()).isZero();

        metrics.markReady(12_500, 10_000);

        assertThat(registry.get("app_startup_duration_seconds").gauge().value()).isEqualTo(2.5);
    }
}
