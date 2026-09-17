// Package data contiene la lógica de negocio del endpoint /data,
// aislada del transporte HTTP para poder probarla sin levantar el servidor.
package data

import (
	"context"
	"errors"
	"time"
)

// SimulatedDelay es la espera que reemplaza a la consulta real cuando
// useDatabase está apagada.
const SimulatedDelay = 50 * time.Millisecond

// MaxUsers limita las filas devueltas por la consulta.
const MaxUsers = 100

// ErrDatabaseUnavailable indica que la consulta a Postgres no pudo realizarse.
var ErrDatabaseUnavailable = errors.New("database unavailable")

// User es la proyección pública de datatest.user. No incluye password.
type User struct {
	ID        string    `json:"id"`
	Email     string    `json:"email"`
	Active    bool      `json:"active"`
	CreatedAt time.Time `json:"createdAt"`
}

// Result es la respuesta del endpoint /data.
type Result struct {
	Source string `json:"source"`
	Count  int    `json:"count"`
	Users  []User `json:"users"`
}

// Repository obtiene usuarios de la base. Se define aquí para que el servicio
// dependa de la interfaz y no de una conexión concreta.
type Repository interface {
	ListUsers(ctx context.Context, limit int) ([]User, error)
}

// Service resuelve /data en sus dos modos.
type Service struct {
	repo  Repository
	delay time.Duration
	sleep func(context.Context, time.Duration) error
}

// NewService construye el servicio con el repositorio dado.
func NewService(repo Repository) *Service {
	return &Service{repo: repo, delay: SimulatedDelay, sleep: sleepContext}
}

// Get devuelve los usuarios de la base cuando useDatabase está prendida, o
// espera SimulatedDelay y devuelve una lista vacía cuando está apagada.
func (s *Service) Get(ctx context.Context, useDatabase bool) (Result, error) {
	if !useDatabase {
		if err := s.sleep(ctx, s.delay); err != nil {
			return Result{}, err
		}
		return Result{Source: "simulation", Count: 0, Users: []User{}}, nil
	}

	if s.repo == nil {
		return Result{}, ErrDatabaseUnavailable
	}

	users, err := s.repo.ListUsers(ctx, MaxUsers)
	if err != nil {
		return Result{}, errors.Join(ErrDatabaseUnavailable, err)
	}
	if users == nil {
		users = []User{}
	}

	return Result{Source: "database", Count: len(users), Users: users}, nil
}

// sleepContext espera d respetando la cancelación del contexto.
func sleepContext(ctx context.Context, d time.Duration) error {
	timer := time.NewTimer(d)
	defer timer.Stop()

	select {
	case <-timer.C:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}
