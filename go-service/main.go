package main

import (
	"context"
	"database/sql"
	"errors"
	"log"
	"net"
	"net/http"
	"os"
	"strconv"
	"time"

	_ "github.com/jackc/pgx/v5/stdlib"
	"github.com/marioferyhwh/comparativo-tec/go-service/internal/data"
	"github.com/marioferyhwh/comparativo-tec/go-service/internal/httpapi"
	"github.com/marioferyhwh/comparativo-tec/go-service/internal/runtimemetrics"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/collectors"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

func main() {
	db, err := openDatabase()
	if err != nil {
		// Sin base el servicio sigue en pie: el modo simulación no la necesita
		// y /data?useDatabase=true responderá 503.
		log.Printf("sin conexión a la base de datos: %v", err)
	}
	if db != nil {
		defer db.Close()
	}

	var repo data.Repository
	if db != nil {
		repo = data.NewPostgresRepository(db)
	}

	registry := prometheus.NewRegistry()
	// CPU, memoria y goroutines del proceso: son la mitad Go de la comparación.
	registry.MustRegister(
		collectors.NewProcessCollector(collectors.ProcessCollectorOpts{}),
		collectors.NewGoCollector(),
		runtimemetrics.NewMemoryCollector(),
	)
	startup := runtimemetrics.NewStartupGauge(registry)
	handler := httpapi.NewHandler(data.NewService(repo), registry)

	mux := http.NewServeMux()
	mux.Handle("/data", handler)
	mux.Handle("/metrics", promhttp.HandlerFor(registry, promhttp.HandlerOpts{}))

	server := &http.Server{
		Addr:              ":" + port(),
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
	}

	// Se abre el socket aparte para marcar el arranque con el servidor ya
	// aceptando conexiones, no antes.
	listener, err := net.Listen("tcp", server.Addr)
	if err != nil {
		log.Fatal(err)
	}
	startup.MarkReady()

	log.Printf("servidor Go escuchando en %s", server.Addr)
	if err := server.Serve(listener); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatal(err)
	}
}

// openDatabase abre el pool contra DATABASE_URL y verifica que responde.
func openDatabase() (*sql.DB, error) {
	dsn := os.Getenv("DATABASE_URL")
	if dsn == "" {
		return nil, errors.New("DATABASE_URL no configurada")
	}

	db, err := sql.Open("pgx", dsn)
	if err != nil {
		return nil, err
	}

	// El pool se configura por entorno para que Go y Java midan en igualdad.
	db.SetMaxOpenConns(envInt("DB_MAX_OPEN_CONNS", 10))
	db.SetMaxIdleConns(envInt("DB_MAX_IDLE_CONNS", 10))
	db.SetConnMaxLifetime(30 * time.Minute)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := db.PingContext(ctx); err != nil {
		db.Close()
		return nil, err
	}

	return db, nil
}

func port() string {
	if p := os.Getenv("PORT"); p != "" {
		return p
	}
	return "8080"
}

func envInt(key string, fallback int) int {
	v, err := strconv.Atoi(os.Getenv(key))
	if err != nil || v <= 0 {
		return fallback
	}
	return v
}
