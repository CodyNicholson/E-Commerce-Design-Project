package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/CodyNicholson/E-Commerce-Design-Project/backend/internal/api"
	"github.com/CodyNicholson/E-Commerce-Design-Project/backend/internal/db"
)

func env(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	dsn := env("DATABASE_URL", fmt.Sprintf("postgres://%s:%s@%s:%s/%s?sslmode=disable",
		env("DB_USER", "postgres"), env("DB_PASSWORD", "postgres"), env("DB_HOST", "localhost"), env("DB_PORT", "5432"), env("DB_NAME", "mro")))
	pool, err := db.Connect(ctx, dsn)
	if err != nil {
		log.Fatal(err)
	}
	defer pool.Close()
	if err := db.EnsureSeeded(ctx, pool); err != nil {
		log.Fatalf("seed: %v", err)
	}

	srv := &http.Server{
		Addr:              ":" + env("PORT", "8080"),
		Handler:           api.New(pool, env("PUBLIC_URL", "http://localhost:8090")).Routes(),
		ReadHeaderTimeout: 10 * time.Second,
	}
	go func() {
		log.Printf("MRO Mirror API listening on %s", srv.Addr)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatal(err)
		}
	}()
	<-ctx.Done()
	shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = srv.Shutdown(shutdown)
}
