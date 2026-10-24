// Package db connects to Postgres and loads the schema and seed data.
package db

import (
	"context"
	_ "embed"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

//go:embed schema.sql
var schemaSQL string

//go:embed seed.sql
var seedSQL string

func Connect(ctx context.Context, url string) (*pgxpool.Pool, error) {
	var lastErr error
	for range 30 {
		pool, err := pgxpool.New(ctx, url)
		if err == nil {
			if err = pool.Ping(ctx); err == nil {
				return pool, nil
			}
			pool.Close()
		}
		lastErr = err
		time.Sleep(time.Second)
	}
	return nil, fmt.Errorf("connect to postgres: %w", lastErr)
}

// EnsureSeeded creates and seeds the schema on first start.
func EnsureSeeded(ctx context.Context, pool *pgxpool.Pool) error {
	var exists bool
	if err := pool.QueryRow(ctx, `SELECT to_regclass('pim.products') IS NOT NULL`).Scan(&exists); err != nil {
		return err
	}
	if exists {
		return nil
	}
	return Reset(ctx, pool)
}

// Reset drops every bounded-context schema and reloads the seed data.
func Reset(ctx context.Context, pool *pgxpool.Pool) error {
	_, err := pool.Exec(ctx, `DROP SCHEMA IF EXISTS keepstock, procurement, oms, pricing, accounts, pim CASCADE`)
	if err != nil {
		return err
	}
	if _, err := pool.Exec(ctx, schemaSQL); err != nil {
		return fmt.Errorf("schema: %w", err)
	}
	if _, err := pool.Exec(ctx, seedSQL); err != nil {
		return fmt.Errorf("seed: %w", err)
	}
	return nil
}
