package runtimemetrics

import (
	"bufio"
	"runtime"
	"strings"
	"testing"

	"github.com/prometheus/client_golang/prometheus"
	dto "github.com/prometheus/client_model/go"
)

func TestParseResidentBytesLeeVmRSSEnBytes(t *testing.T) {
	status := "Name:\tmain\nVmPeak:\t  9000 kB\nVmRSS:\t   2048 kB\nThreads:\t8\n"

	got := parseResidentBytes(bufio.NewScanner(strings.NewReader(status)))

	if got != 2048*1024 {
		t.Errorf("rss = %v, quiero %v", got, 2048*1024)
	}
}

func TestParseResidentBytesSinLineaDevuelveCero(t *testing.T) {
	if got := parseResidentBytes(bufio.NewScanner(strings.NewReader("Name:\tmain\n"))); got != 0 {
		t.Errorf("rss = %v, quiero 0", got)
	}
}

// TestMemoriaPublicaLosTresTipos fija los valores del label type, que tienen
// que coincidir con los de ProcessRuntimeMetrics en Java.
func TestMemoriaPublicaLosTresTipos(t *testing.T) {
	c := &memoryCollector{
		desc:    NewMemoryCollector().(*memoryCollector).desc,
		readRSS: func() float64 { return 300 },
		readMemory: func(m *runtime.MemStats) {
			m.HeapSys, m.HeapReleased, m.HeapAlloc = 250, 50, 120
		},
	}

	reg := prometheus.NewRegistry()
	reg.MustRegister(c)
	familias, err := reg.Gather()
	if err != nil {
		t.Fatal(err)
	}

	got := map[string]float64{}
	for _, m := range familias[0].GetMetric() {
		got[m.GetLabel()[0].GetValue()] = m.GetGauge().GetValue()
	}
	quiero := map[string]float64{TypeRSS: 300, TypeHeapTotal: 200, TypeHeapUsed: 120}
	if len(got) != len(quiero) {
		t.Fatalf("tipos = %v, quiero %v", got, quiero)
	}
	for tipo, v := range quiero {
		if got[tipo] != v {
			t.Errorf("%s = %v, quiero %v", tipo, got[tipo], v)
		}
	}
}

func TestArranqueValeCeroHastaMarcarListo(t *testing.T) {
	reg := prometheus.NewRegistry()
	s := NewStartupGauge(reg)

	if v := valor(t, s.gauge); v != 0 {
		t.Fatalf("antes de MarkReady = %v, quiero 0", v)
	}
	s.MarkReady()
	if v := valor(t, s.gauge); v <= 0 {
		t.Errorf("después de MarkReady = %v, quiero > 0", v)
	}
}

func valor(t *testing.T, g prometheus.Gauge) float64 {
	t.Helper()
	var m dto.Metric
	if err := g.Write(&m); err != nil {
		t.Fatal(err)
	}
	return m.GetGauge().GetValue()
}
