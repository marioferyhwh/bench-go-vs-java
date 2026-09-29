package com.comparativa.httpapi;

import com.sun.management.OperatingSystemMXBean;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Publica las métricas de proceso con los mismos nombres, labels y unidades
 * que el servicio Go:
 *
 * <ul>
 *   <li>process_resident_memory_bytes y process_cpu_seconds_total, que en Go
 *       trae el cliente de Prometheus.</li>
 *   <li>process_network_receive_bytes_total y
 *       process_network_transmit_bytes_total, que también trae el cliente de Go
 *       leyendo /proc/self/net/netstat.</li>
 *   <li>app_memory_usage_bytes{type}, el desglose rss, heap_total y heap_used
 *       que Go publica desde el paquete runtimemetrics.</li>
 * </ul>
 *
 * <p>Micrometer no trae ninguna con estos nombres: expone la memoria solo como
 * pools de la JVM, la CPU como process_cpu_time_ns en nanosegundos y nada de
 * red. Sin esta clase el dashboard compararía métricas distintas entre una
 * tecnología y la otra.
 */
@Component
public class ProcessRuntimeMetrics {

    private static final Path STATUS = Path.of("/proc/self/status");
    private static final Path NETSTAT = Path.of("/proc/self/net/netstat");
    private static final String RSS_PREFIX = "VmRSS:";
    private static final String IP_EXT_PREFIX = "IpExt:";
    private static final double NANOS_PER_SECOND = 1_000_000_000d;

    /** Valores del label type, iguales a los de runtimemetrics en Go. */
    static final String TYPE_RSS = "rss";
    static final String TYPE_HEAP_TOTAL = "heap_total";
    static final String TYPE_HEAP_USED = "heap_used";

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

        registerMemoryUsage(registry);
        registerNetwork(registry);
    }

    /**
     * El desglose de memoria del video de referencia. heap_total es el heap
     * que la JVM tiene reservado del sistema (committed) y heap_used el que
     * ocupan los objetos (used). En Go son HeapSys - HeapReleased y HeapAlloc.
     */
    private static void registerMemoryUsage(MeterRegistry registry) {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        String description = "Memoria del proceso en bytes, por tipo (rss, heap_total, heap_used).";

        Gauge.builder("app_memory_usage_bytes", ProcessRuntimeMetrics::readResidentBytes)
                .description(description)
                .baseUnit("bytes")
                .tag("type", TYPE_RSS)
                .register(registry);
        Gauge.builder("app_memory_usage_bytes", memory, m -> m.getHeapMemoryUsage().getCommitted())
                .description(description)
                .baseUnit("bytes")
                .tag("type", TYPE_HEAP_TOTAL)
                .register(registry);
        Gauge.builder("app_memory_usage_bytes", memory, m -> m.getHeapMemoryUsage().getUsed())
                .description(description)
                .baseUnit("bytes")
                .tag("type", TYPE_HEAP_USED)
                .register(registry);
    }

    /**
     * Bytes de red recibidos y enviados, de la misma fuente que Go: los
     * contadores InOctets y OutOctets de la línea IpExt. Cuentan todo el
     * espacio de red del contenedor, que en la práctica es solo este proceso.
     */
    private static void registerNetwork(MeterRegistry registry) {
        FunctionCounter.builder("process_network_receive", NETSTAT, p -> readIpExtOctets(p, "InOctets"))
                .description("Bytes recibidos por el proceso a través de la red.")
                .baseUnit("bytes")
                .register(registry);
        FunctionCounter.builder("process_network_transmit", NETSTAT, p -> readIpExtOctets(p, "OutOctets"))
                .description("Bytes enviados por el proceso a través de la red.")
                .baseUnit("bytes")
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
            return parseResidentBytes(Files.readAllLines(STATUS));
        } catch (IOException e) {
            return 0;
        }
    }

    /** Busca la línea "VmRSS:     123456 kB" y la pasa a bytes. */
    static double parseResidentBytes(List<String> lines) {
        for (String line : lines) {
            if (line.startsWith(RSS_PREFIX)) {
                try {
                    String[] parts = line.split("\\s+");
                    return Double.parseDouble(parts[1]) * 1024;
                } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static double readIpExtOctets(Path netstat, String field) {
        try {
            return parseIpExtOctets(Files.readAllLines(netstat), field);
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * En /proc/self/net/netstat cada grupo ocupa dos líneas con el mismo
     * prefijo: la primera trae los nombres de los campos y la segunda los
     * valores. Devuelve el valor del campo pedido del grupo IpExt, o 0 si no
     * aparece.
     */
    static double parseIpExtOctets(List<String> lines, String field) {
        for (int i = 0; i + 1 < lines.size(); i++) {
            if (!lines.get(i).startsWith(IP_EXT_PREFIX) || !lines.get(i + 1).startsWith(IP_EXT_PREFIX)) {
                continue;
            }
            String[] names = lines.get(i).trim().split("\\s+");
            String[] values = lines.get(i + 1).trim().split("\\s+");
            for (int j = 1; j < names.length && j < values.length; j++) {
                if (names[j].equals(field)) {
                    try {
                        return Double.parseDouble(values[j]);
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                }
            }
            return 0;
        }
        return 0;
    }
}
