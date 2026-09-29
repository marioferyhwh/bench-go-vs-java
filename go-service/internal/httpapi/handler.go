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

// Route es el valor del label route. El servicio solo mide /data, así que es
// constante, pero el label deja el esquema igual al de Java y al del video de
// referencia (method, route, status).
const Route = "/data"

// knownMethods acota el label method: un método arbitrario enviado por un
// cliente crearía una serie nueva por cada valor distinto.
var knownMethods = map[string]bool{
	http.MethodGet: true, http.MethodHead: true, http.MethodPost: true,
	http.MethodPut: true, http.MethodPatch: true, http.MethodDelete: true,
	http.MethodOptions: true,
}

// metricLabels son los labels de las tres métricas HTTP, en el mismo orden que
// DataMetrics en Java.
var metricLabels = []string{"method", "route", "status", "mode"}

type errorBody struct {
	Error string `json:"error"`
}

// Handler expone GET /data.
type Handler struct {
	svc       *data.Service
	requests  *prometheus.CounterVec
	errors    *prometheus.CounterVec
	latencies *prometheus.HistogramVec
}

// NewHandler registra las métricas en el registry dado y devuelve el handler.
func NewHandler(svc *data.Service, reg prometheus.Registerer) *Handler {
	requests := prometheus.NewCounterVec(
		prometheus.CounterOpts{
			Name: "http_requests_total",
			Help: "Total de peticiones HTTP a /data.",
		},
		metricLabels,
	)
	// Contador aparte para status >= 400. Se puede deducir de
	// http_requests_total, pero así el error rate es una consulta directa
	// sobre una serie pequeña, como propone el video de referencia.
	errorsTotal := prometheus.NewCounterVec(
		prometheus.CounterOpts{
			Name: "http_errors_total",
			Help: "Total de respuestas de /data con status >= 400.",
		},
		metricLabels,
	)
	latencies := prometheus.NewHistogramVec(
		prometheus.HistogramOpts{
			Name:    "http_request_duration_seconds",
			Help:    "Latencia de /data en segundos.",
			Buckets: LatencyBuckets,
		},
		metricLabels,
	)
	reg.MustRegister(requests, errorsTotal, latencies)

	return &Handler{svc: svc, requests: requests, errors: errorsTotal, latencies: latencies}
}

// ServeHTTP resuelve GET /data?useDatabase=<true|false>.
func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	start := time.Now()

	if r.Method != http.MethodGet {
		h.write(w, r, http.StatusMethodNotAllowed, "unknown", start, errorBody{Error: "method not allowed"})
		return
	}

	useDatabase, err := parseUseDatabase(r.URL.Query().Get("useDatabase"))
	if err != nil {
		h.write(w, r, http.StatusBadRequest, "unknown", start, errorBody{Error: "invalid useDatabase value"})
		return
	}

	mode := "simulation"
	if useDatabase {
		mode = "database"
	}

	result, err := h.svc.Get(r.Context(), useDatabase)
	if err != nil {
		if errors.Is(err, data.ErrDatabaseUnavailable) {
			h.write(w, r, http.StatusServiceUnavailable, mode, start, errorBody{Error: "database unavailable"})
			return
		}
		h.write(w, r, http.StatusInternalServerError, mode, start, errorBody{Error: "internal error"})
		return
	}

	h.write(w, r, http.StatusOK, mode, start, result)
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

// write serializa el cuerpo y después registra las métricas de la petición.
//
// La latencia se observa tras serializar, no antes: Java la mide en
// MetricsFilter, que envuelve también la escritura del JSON, y el video de
// referencia mide hasta que la respuesta se envía. Parar antes dejaba fuera de
// Go el coste de codificar hasta 100 usuarios que Java sí pagaba.
func (h *Handler) write(w http.ResponseWriter, r *http.Request, status int, mode string, start time.Time, body any) {
	writeJSON(w, status, body)

	labels := prometheus.Labels{
		"method": methodLabel(r.Method),
		"route":  Route,
		"status": strconv.Itoa(status),
		"mode":   mode,
	}
	h.requests.With(labels).Inc()
	if status >= http.StatusBadRequest {
		h.errors.With(labels).Inc()
	}
	h.latencies.With(labels).Observe(time.Since(start).Seconds())
}

// methodLabel devuelve el método tal cual si es estándar, u "OTHER" si no.
func methodLabel(method string) string {
	if knownMethods[method] {
		return method
	}
	return "OTHER"
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
