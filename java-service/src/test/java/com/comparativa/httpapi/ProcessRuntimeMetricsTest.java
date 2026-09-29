package com.comparativa.httpapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Comprueba que las métricas de proceso salen con los mismos nombres y labels
 * que en Go, y que la lectura de /proc da los mismos valores.
 */
class ProcessRuntimeMetricsTest {

    @Test
    void leeVmRssEnBytes() {
        List<String> status = List.of("Name:\tjava", "VmPeak:\t  9000 kB", "VmRSS:\t   2048 kB");

        assertThat(ProcessRuntimeMetrics.parseResidentBytes(status)).isEqualTo(2048 * 1024d);
    }

    @Test
    void sinVmRssDevuelveCero() {
        assertThat(ProcessRuntimeMetrics.parseResidentBytes(List.of("Name:\tjava"))).isZero();
    }

    /** Mismo formato que /proc/self/net/netstat: nombres en una línea, valores en la siguiente. */
    @Test
    void leeInOctetsYOutOctetsDeIpExt() {
        List<String> netstat = List.of(
                "TcpExt: SyncookiesSent SyncookiesRecv",
                "TcpExt: 0 0",
                "IpExt: InNoRoutes InTruncatedPkts InMcastPkts OutMcastPkts InBcastPkts OutBcastPkts InOctets OutOctets",
                "IpExt: 0 0 0 0 0 0 123456 654321");

        assertThat(ProcessRuntimeMetrics.parseIpExtOctets(netstat, "InOctets")).isEqualTo(123456d);
        assertThat(ProcessRuntimeMetrics.parseIpExtOctets(netstat, "OutOctets")).isEqualTo(654321d);
    }

    @Test
    void sinIpExtDevuelveCero() {
        assertThat(ProcessRuntimeMetrics.parseIpExtOctets(List.of("TcpExt: A", "TcpExt: 1"), "InOctets"))
                .isZero();
    }

    /** Los valores del label type tienen que coincidir con runtimemetrics en Go. */
    @Test
    void publicaLosTresTiposDeMemoriaYLaRed() {
        MeterRegistry registry = new SimpleMeterRegistry();
        new ProcessRuntimeMetrics(registry);

        for (String tipo : List.of("rss", "heap_total", "heap_used")) {
            assertThat(registry.find("app_memory_usage_bytes").tag("type", tipo).gauge())
                    .as(tipo)
                    .isNotNull();
        }
        assertThat(registry.find("process_network_receive").functionCounter()).isNotNull();
        assertThat(registry.find("process_network_transmit").functionCounter()).isNotNull();
    }
}
