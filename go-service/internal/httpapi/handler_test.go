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
