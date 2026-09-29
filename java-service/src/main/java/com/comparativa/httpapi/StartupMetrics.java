package com.comparativa.httpapi;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Publica app_startup_duration_seconds: segundos desde que arrancó la JVM
 * hasta que la aplicación está lista para atender, con Tomcat ya escuchando.
 *
 * <p>Es el espejo de StartupGauge en Go, que se marca con el socket ya
 * abierto. Vale 0 hasta que llega ApplicationReadyEvent.
 */
@Component
public class StartupMetrics {

    private final AtomicLong startupMillis = new AtomicLong();

    public StartupMetrics(MeterRegistry registry) {
        Gauge.builder("app_startup_duration_seconds", startupMillis, ms -> ms.get() / 1000d)
                .description("Segundos desde el inicio del proceso hasta que el servidor acepta peticiones.")
                .baseUnit("seconds")
                .register(registry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        markReady(System.currentTimeMillis(), ManagementFactory.getRuntimeMXBean().getStartTime());
    }

    /** Separado del evento para poder probarlo sin levantar Spring. */
    void markReady(long nowMillis, long jvmStartMillis) {
        startupMillis.set(Math.max(0, nowMillis - jvmStartMillis));
    }
}
