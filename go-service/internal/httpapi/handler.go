// Package httpapi traduce peticiones HTTP a llamadas del servicio de datos.
// No contiene lógica de negocio: solo lee el query param y serializa.
package httpapi

import (
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"time"

	"github.com/marioferyhwh/comparativo-tec/go-service/internal/data"
	"github.com/prometheus/client_golang/prometheus"
)

// LatencyBuckets son los cubos del histograma de latencia, en segundos. La
// lista es idéntica a la del servicio Java y no debe cambiar en una sola de
// las dos: histogram_quantile interpola dentro del cubo donde cae el
// percentil, así que cubos distintos producen percentiles con precisión
// distinta y la comparación deja de medir las tecnologías para medir la
// instrumentación.
//
// Es densa entre 1 y 100 ms, que es donde viven estas latencias, y mantiene
// una cola hasta 10 s para los arranques en frío de la JVM.
var LatencyBuckets = []float64{
	0.001, 0.002, 0.003, 0.005, 0.0075, 0.010, 0.015, 0.025,
	0.050, 0.075, 0.100, 0.250, 0.500, 1.0, 2.5, 10.0,
}

type errorBody struct {
	Error string `json:"error"`
}

// Handler expone GET /data.
type Handler struct {
	svc       *data.Service
	requests  *prometheus.CounterVec
	latencies *prometheus.HistogramVec
}

// NewHandler registra las métricas en el registry dado y devuelve el handler.
func NewHandler(svc *data.Service, reg prometheus.Registerer) *Handler {
	requests := prometheus.NewCounterVec(
		prometheus.CounterOpts{
			Name: "http_requests_total",
			Help: "Total de peticiones HTTP a /data.",
		},
		[]string{"status", "mode"},
	)
	latencies := prometheus.NewHistogramVec(
		prometheus.HistogramOpts{
			Name:    "http_request_duration_seconds",
			Help:    "Latencia de /data en segundos.",
			Buckets: LatencyBuckets,
		},
		[]string{"status", "mode"},
	)
	reg.MustRegister(requests, latencies)

	return &Handler{svc: svc, requests: requests, latencies: latencies}
}

// ServeHTTP resuelve GET /data?useDatabase=<true|false>.
func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	start := time.Now()

	if r.Method != http.MethodGet {
		h.write(w, http.StatusMethodNotAllowed, "unknown", start, errorBody{Error: "method not allowed"})
		return
	}

	useDatabase, err := parseUseDatabase(r.URL.Query().Get("useDatabase"))
	if err != nil {
		h.write(w, http.StatusBadRequest, "unknown", start, errorBody{Error: "invalid useDatabase value"})
		return
	}

	mode := "simulation"
	if useDatabase {
		mode = "database"
	}

	result, err := h.svc.Get(r.Context(), useDatabase)
	if err != nil {
		if errors.Is(err, data.ErrDatabaseUnavailable) {
			h.write(w, http.StatusServiceUnavailable, mode, start, errorBody{Error: "database unavailable"})
			return
		}
		h.write(w, http.StatusInternalServerError, mode, start, errorBody{Error: "internal error"})
		return
	}

	h.write(w, http.StatusOK, mode, start, result)
}

// parseUseDatabase acepta solo "true", "false" o vacío (equivalente a false).
func parseUseDatabase(raw string) (bool, error) {
	if raw == "" {
		return false, nil
	}
	if raw != "true" && raw != "false" {
		return false, strconv.ErrSyntax
	}
	return raw == "true", nil
}

// write serializa el cuerpo y registra las métricas de la petición.
func (h *Handler) write(w http.ResponseWriter, status int, mode string, start time.Time, body any) {
	labels := prometheus.Labels{"status": strconv.Itoa(status), "mode": mode}
	h.requests.With(labels).Inc()
	h.latencies.With(labels).Observe(time.Since(start).Seconds())

	writeJSON(w, status, body)
}

// writeJSON serializa el cuerpo como JSON con el código dado.
func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(body); err != nil {
		// La respuesta ya empezó a escribirse; solo queda dejar rastro.
		http.Error(w, "", http.StatusInternalServerError)
	}
}
