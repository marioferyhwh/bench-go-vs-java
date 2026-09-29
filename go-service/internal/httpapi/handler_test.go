package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/marioferyhwh/comparativo-tec/go-service/internal/data"
	"github.com/prometheus/client_golang/prometheus"
)

func newTestHandler(repo data.Repository) *Handler {
	return NewHandler(data.NewService(repo), prometheus.NewRegistry())
}

func TestDataSinParamsUsaSimulacion(t *testing.T) {
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/data", nil)

	newTestHandler(nil).ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, se esperaba 200", rec.Code)
	}
	if ct := rec.Header().Get("Content-Type"); ct != "application/json" {
		t.Errorf("Content-Type = %q, se esperaba application/json", ct)
	}

	var body data.Result
	if err := json.NewDecoder(rec.Body).Decode(&body); err != nil {
		t.Fatalf("cuerpo no es JSON válido: %v", err)
	}
	if body.Source != "simulation" {
		t.Errorf("source = %q, se esperaba simulation", body.Source)
	}
}

func TestDataConValorInvalidoDevuelve400(t *testing.T) {
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/data?useDatabase=yes", nil)

	newTestHandler(nil).ServeHTTP(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, se esperaba 400", rec.Code)
	}
	if !strings.Contains(rec.Body.String(), "invalid useDatabase value") {
		t.Errorf("cuerpo = %q, se esperaba el error de useDatabase", rec.Body.String())
	}
}

func TestMetodoNoPermitidoDevuelve405(t *testing.T) {
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/data", nil)

	newTestHandler(nil).ServeHTTP(rec, req)

	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("status = %d, se esperaba 405", rec.Code)
	}
}

func TestDataSinBaseDevuelve503(t *testing.T) {
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/data?useDatabase=true", nil)

	newTestHandler(nil).ServeHTTP(rec, req)

	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, se esperaba 503", rec.Code)
	}
}

func TestRespuestaNoIncluyePassword(t *testing.T) {
	repo := stubRepo{users: []data.User{
		{ID: "1", Email: "admin@datatest.com", Active: true},
	}}
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/data?useDatabase=true", nil)

	newTestHandler(repo).ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, se esperaba 200", rec.Code)
	}
	if strings.Contains(strings.ToLower(rec.Body.String()), "password") {
		t.Errorf("la respuesta expone password: %s", rec.Body.String())
	}
}

type stubRepo struct {
	users []data.User
}

func (s stubRepo) ListUsers(context.Context, int) ([]data.User, error) {
	return s.users, nil
}

// TestCubosDeLatenciaCoincidenConJava fija la lista de cubos para que no
// cambie en Go sin cambiarla también en Java. Cubos distintos entre las dos
// tecnologías producen percentiles con precisión distinta, y la comparación
// pasa a medir la instrumentación en vez de las tecnologías.
//
// La lista espejo vive en DataMetrics.LATENCY_BUCKETS_SECONDS.
func TestCubosDeLatenciaCoincidenConJava(t *testing.T) {
	esperados := []float64{
		0.001, 0.002, 0.003, 0.005, 0.0075, 0.010, 0.015, 0.025,
		0.050, 0.075, 0.100, 0.250, 0.500, 1.0, 2.5, 10.0,
	}

	if len(LatencyBuckets) != len(esperados) {
		t.Fatalf("cubos: %d, esperados %d", len(LatencyBuckets), len(esperados))
	}
	for i, quiero := range esperados {
		if LatencyBuckets[i] != quiero {
			t.Errorf("cubo %d: %v, quiero %v", i, LatencyBuckets[i], quiero)
		}
	}
}

// TestMetricasLlevanMethodRouteStatusMode fija el esquema de labels, igual al
// de DataMetrics en Java: method, route, status y mode.
func TestMetricasLlevanMethodRouteStatusMode(t *testing.T) {
	reg := prometheus.NewRegistry()
	h := NewHandler(data.NewService(nil), reg)

	h.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(http.MethodGet, "/data?useDatabase=yes", nil))

	quiero := map[string]string{"method": "GET", "route": "/data", "status": "400", "mode": "unknown"}
	for _, nombre := range []string{"http_requests_total", "http_errors_total", "http_request_duration_seconds"} {
		if got := labelsDe(t, reg, nombre); !mismosLabels(got, quiero) {
			t.Errorf("%s: labels %v, quiero %v", nombre, got, quiero)
		}
	}
}

// TestErroresSoloCuentanStatusDe400EnAdelante comprueba que un 200 no suma
// en http_errors_total y un 405 sí, con method="POST".
func TestErroresSoloCuentanStatusDe400EnAdelante(t *testing.T) {
	reg := prometheus.NewRegistry()
	h := NewHandler(data.NewService(stubRepo{}), reg)

	h.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(http.MethodGet, "/data?useDatabase=true", nil))
	h.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(http.MethodPost, "/data", nil))

	familias, err := reg.Gather()
	if err != nil {
		t.Fatal(err)
	}
	for _, f := range familias {
		if f.GetName() != "http_errors_total" {
			continue
		}
		if len(f.GetMetric()) != 1 {
			t.Fatalf("http_errors_total: %d series, quiero 1 (solo el 405)", len(f.GetMetric()))
		}
		labels := map[string]string{}
		for _, l := range f.GetMetric()[0].GetLabel() {
			labels[l.GetName()] = l.GetValue()
		}
		if labels["status"] != "405" || labels["method"] != "POST" {
			t.Errorf("serie de error inesperada: %v", labels)
		}
		return
	}
	t.Fatal("no se publicó http_errors_total")
}

func TestMetodoDesconocidoSeEtiquetaOther(t *testing.T) {
	if got := methodLabel("BREW"); got != "OTHER" {
		t.Errorf("methodLabel(BREW) = %q, quiero OTHER", got)
	}
	if got := methodLabel(http.MethodGet); got != "GET" {
		t.Errorf("methodLabel(GET) = %q, quiero GET", got)
	}
}

func labelsDe(t *testing.T, reg *prometheus.Registry, nombre string) map[string]string {
	t.Helper()
	familias, err := reg.Gather()
	if err != nil {
		t.Fatal(err)
	}
	for _, f := range familias {
		if f.GetName() != nombre || len(f.GetMetric()) == 0 {
			continue
		}
		labels := map[string]string{}
		for _, l := range f.GetMetric()[0].GetLabel() {
			labels[l.GetName()] = l.GetValue()
		}
		return labels
	}
	t.Fatalf("no se publicó %s", nombre)
	return nil
}

func mismosLabels(a, b map[string]string) bool {
	if len(a) != len(b) {
		return false
	}
	for k, v := range b {
		if a[k] != v {
			return false
		}
	}
	return true
}
