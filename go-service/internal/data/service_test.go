package data

import (
	"context"
	"errors"
	"testing"
	"time"
)

// stubRepo devuelve usuarios fijos o un error, sin tocar Postgres.
type stubRepo struct {
	users []User
	err   error
}

func (s stubRepo) ListUsers(context.Context, int) ([]User, error) {
	return s.users, s.err
}

func TestGetSimulationEsperaElRetardo(t *testing.T) {
	svc := NewService(nil)

	start := time.Now()
	got, err := svc.Get(context.Background(), false)
	elapsed := time.Since(start)

	if err != nil {
		t.Fatalf("error inesperado: %v", err)
	}
	if elapsed < SimulatedDelay {
		t.Errorf("esperó %v, se esperaba al menos %v", elapsed, SimulatedDelay)
	}
	if got.Source != "simulation" {
		t.Errorf("source = %q, se esperaba %q", got.Source, "simulation")
	}
	if got.Count != 0 {
		t.Errorf("count = %d, se esperaba 0", got.Count)
	}
	if got.Users == nil {
		t.Error("users es nil, se esperaba una lista vacía")
	}
}

func TestGetDatabaseDevuelveLosUsuarios(t *testing.T) {
	repo := stubRepo{users: []User{
		{ID: "1", Email: "admin@datatest.com", Active: true, CreatedAt: time.Now()},
		{ID: "2", Email: "teacher@datatest.com", Active: true, CreatedAt: time.Now()},
	}}
	svc := NewService(repo)

	got, err := svc.Get(context.Background(), true)

	if err != nil {
		t.Fatalf("error inesperado: %v", err)
	}
	if got.Source != "database" {
		t.Errorf("source = %q, se esperaba %q", got.Source, "database")
	}
	if got.Count != 2 {
		t.Errorf("count = %d, se esperaba 2", got.Count)
	}
	if len(got.Users) != 2 {
		t.Fatalf("len(users) = %d, se esperaba 2", len(got.Users))
	}
	if got.Users[0].Email != "admin@datatest.com" {
		t.Errorf("email = %q, se esperaba %q", got.Users[0].Email, "admin@datatest.com")
	}
}

func TestGetDatabaseSinRepositorioFalla(t *testing.T) {
	svc := NewService(nil)

	_, err := svc.Get(context.Background(), true)

	if !errors.Is(err, ErrDatabaseUnavailable) {
		t.Errorf("err = %v, se esperaba ErrDatabaseUnavailable", err)
	}
}

func TestGetDatabaseConErrorDelRepositorio(t *testing.T) {
	repo := stubRepo{err: errors.New("conexión rechazada")}
	svc := NewService(repo)

	_, err := svc.Get(context.Background(), true)

	if !errors.Is(err, ErrDatabaseUnavailable) {
		t.Errorf("err = %v, se esperaba ErrDatabaseUnavailable", err)
	}
}

func TestGetDatabaseSinFilasDevuelveListaVacia(t *testing.T) {
	svc := NewService(stubRepo{users: nil})

	got, err := svc.Get(context.Background(), true)

	if err != nil {
		t.Fatalf("error inesperado: %v", err)
	}
	if got.Users == nil {
		t.Error("users es nil, se esperaba una lista vacía")
	}
	if got.Count != 0 {
		t.Errorf("count = %d, se esperaba 0", got.Count)
	}
}

func TestGetSimulationRespetaLaCancelacion(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	_, err := NewService(nil).Get(ctx, false)

	if !errors.Is(err, context.Canceled) {
		t.Errorf("err = %v, se esperaba context.Canceled", err)
	}
}
