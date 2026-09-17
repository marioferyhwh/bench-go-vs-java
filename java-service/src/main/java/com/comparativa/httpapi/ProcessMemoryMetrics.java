package com.comparativa.httpapi;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Publica process_resident_memory_bytes leyendo /proc/self/status, la misma
 * fuente que usa el cliente de Prometheus en Go. Micrometer no trae esta
 * métrica, y sin ella el dashboard compararía la memoria residente de Go
 * contra la suma de pools de la JVM, que no es lo mismo.
 */
@Component
public class ProcessMemoryMetrics {

    private static final Path STATUS = Path.of("/proc/self/status");
    private static final String RSS_PREFIX = "VmRSS:";

    public ProcessMemoryMetrics(MeterRegistry registry) {
        Gauge.builder("process_resident_memory_bytes", ProcessMemoryMetrics::readResidentBytes)
                .description("Memoria residente del proceso en bytes.")
                .baseUnit("bytes")
                .register(registry);
    }

    /**
     * Devuelve VmRSS en bytes, o 0 si el sistema no expone /proc (por ejemplo
     * al correr fuera de Linux). Un 0 se ve en la gráfica; una excepción
     * tumbaría el endpoint de métricas.
     */
    private static double readResidentBytes() {
        try {
            List<String> lines = Files.readAllLines(STATUS);
            for (String line : lines) {
                if (line.startsWith(RSS_PREFIX)) {
                    // Formato: "VmRSS:     123456 kB"
                    String[] parts = line.split("\\s+");
                    return Double.parseDouble(parts[1]) * 1024;
                }
            }
        } catch (IOException | NumberFormatException | ArrayIndexOutOfBoundsException e) {
            return 0;
        }
        return 0;
    }
}
