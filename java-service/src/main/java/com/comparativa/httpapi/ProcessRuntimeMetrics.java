package com.comparativa.httpapi;

import com.sun.management.OperatingSystemMXBean;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Publica process_resident_memory_bytes y process_cpu_seconds_total con los
 * mismos nombres y unidades que el cliente de Prometheus en Go.
 *
 * <p>Micrometer no trae ninguna de las dos: expone la memoria solo como pools
 * de la JVM, y la CPU como process_cpu_time_ns en nanosegundos. Sin esta clase
 * el dashboard compararía métricas distintas entre una tecnología y la otra.
 */
@Component
public class ProcessRuntimeMetrics {

    private static final Path STATUS = Path.of("/proc/self/status");
    private static final String RSS_PREFIX = "VmRSS:";
    private static final double NANOS_PER_SECOND = 1_000_000_000d;

    public ProcessRuntimeMetrics(MeterRegistry registry) {
        Gauge.builder("process_resident_memory_bytes", ProcessRuntimeMetrics::readResidentBytes)
                .description("Memoria residente del proceso en bytes.")
                .baseUnit("bytes")
                .register(registry);

        // Contador: solo crece, igual que en Go, para poder usar increase().
        // El nombre va sin sufijo porque el registro de Prometheus le añade
        // "_seconds_total" a partir de la unidad base.
        FunctionCounter.builder(
                        "process_cpu",
                        ManagementFactory.getOperatingSystemMXBean(),
                        ProcessRuntimeMetrics::readCpuSeconds)
                .description("Segundos de CPU acumulados por el proceso.")
                .baseUnit("seconds")
                .register(registry);
    }

    /**
     * Segundos de CPU consumidos por el proceso, o 0 si la JVM no expone el
     * dato extendido (ocurre fuera de HotSpot).
     */
    private static double readCpuSeconds(java.lang.management.OperatingSystemMXBean bean) {
        if (bean instanceof OperatingSystemMXBean sunBean) {
            long nanos = sunBean.getProcessCpuTime();
            return nanos < 0 ? 0 : nanos / NANOS_PER_SECOND;
        }
        return 0;
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
