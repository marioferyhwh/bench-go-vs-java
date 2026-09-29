// Package runtimemetrics publica las métricas de runtime que Go y Java
// exponen con el mismo nombre, labels y unidad: el desglose de memoria y el
// tiempo de arranque. El espejo en Java vive en ProcessRuntimeMetrics y
// StartupMetrics.
package runtimemetrics

import (
	"bufio"
	"os"
	"runtime"
	"strconv"
	"strings"
	"time"

	"github.com/prometheus/client_golang/prometheus"
)

// processStart se fija al inicializar el paquete, antes de main. Deja fuera
// solo la carga del binario y la inicialización del runtime, que son del orden
// de milisegundos; Java mide desde el arranque de la JVM.
var processStart = time.Now()

// statusPath es la fuente del RSS, la misma que lee Java.
const statusPath = "/proc/self/status"

// Tipos del label type de app_memory_usage_bytes, iguales en las dos
// tecnologías:
//   - rss: memoria residente del proceso, VmRSS de /proc/self/status.
//   - heap_total: heap que el runtime tiene reservado del sistema. En Go,
//     HeapSys - HeapReleased; en Java, el committed del heap.
//   - heap_used: heap ocupado por objetos. En Go, HeapAlloc; en Java, el used
//     del heap.
const (
	TypeRSS       = "rss"
	TypeHeapTotal = "heap_total"
	TypeHeapUsed  = "heap_used"
)

// memoryCollector lee la memoria en cada raspado, así el valor nunca está
// desfasado respecto al instante en que Prometheus lo pide.
type memoryCollector struct {
	desc       *prometheus.Desc
	readRSS    func() float64
	readMemory func(*runtime.MemStats)
}

// NewMemoryCollector devuelve el recolector de app_memory_usage_bytes.
func NewMemoryCollector() prometheus.Collector {
	return &memoryCollector{
		desc: prometheus.NewDesc(
			"app_memory_usage_bytes",
			"Memoria del proceso en bytes, por tipo (rss, heap_total, heap_used).",
			[]string{"type"}, nil,
		),
		readRSS:    readResidentBytes,
		readMemory: runtime.ReadMemStats,
	}
}

func (c *memoryCollector) Describe(ch chan<- *prometheus.Desc) {
	ch <- c.desc
}

func (c *memoryCollector) Collect(ch chan<- prometheus.Metric) {
	var m runtime.MemStats
	c.readMemory(&m)

	ch <- prometheus.MustNewConstMetric(c.desc, prometheus.GaugeValue, c.readRSS(), TypeRSS)
	ch <- prometheus.MustNewConstMetric(c.desc, prometheus.GaugeValue, float64(m.HeapSys-m.HeapReleased), TypeHeapTotal)
	ch <- prometheus.MustNewConstMetric(c.desc, prometheus.GaugeValue, float64(m.HeapAlloc), TypeHeapUsed)
}

// readResidentBytes devuelve VmRSS en bytes, o 0 si el sistema no expone
// /proc (por ejemplo al correr fuera de Linux). Un 0 se ve en la gráfica; un
// error tumbaría el raspado entero.
func readResidentBytes() float64 {
	f, err := os.Open(statusPath)
	if err != nil {
		return 0
	}
	defer f.Close()
	return parseResidentBytes(bufio.NewScanner(f))
}

// parseResidentBytes busca la línea "VmRSS:     123456 kB".
func parseResidentBytes(sc *bufio.Scanner) float64 {
	for sc.Scan() {
		line := sc.Text()
		if !strings.HasPrefix(line, "VmRSS:") {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) < 2 {
			return 0
		}
		kb, err := strconv.ParseFloat(fields[1], 64)
		if err != nil {
			return 0
		}
		return kb * 1024
	}
	return 0
}

// StartupGauge publica app_startup_duration_seconds. Vale 0 hasta que se
// llama a MarkReady.
type StartupGauge struct {
	gauge prometheus.Gauge
	start time.Time
}

// NewStartupGauge registra el gauge en el registry dado.
func NewStartupGauge(reg prometheus.Registerer) *StartupGauge {
	g := prometheus.NewGauge(prometheus.GaugeOpts{
		Name: "app_startup_duration_seconds",
		Help: "Segundos desde el inicio del proceso hasta que el servidor acepta peticiones.",
	})
	reg.MustRegister(g)
	return &StartupGauge{gauge: g, start: processStart}
}

// MarkReady fija el tiempo de arranque. Se llama con el socket ya abierto,
// justo antes de empezar a servir, que es el equivalente de
// ApplicationReadyEvent en Spring.
func (s *StartupGauge) MarkReady() {
	s.gauge.Set(time.Since(s.start).Seconds())
}
