// Package api exposes the bounded contexts over HTTP and orchestrates the
// cross-context flows (checkout touches PIM, Accounts, Pricing and OMS).
package api

import (
	"context"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/CodyNicholson/E-Commerce-Design-Project/backend/internal/db"
)

// dbtx is satisfied by both the pool and a transaction.
type dbtx interface {
	Exec(ctx context.Context, sql string, args ...any) (pgconn.CommandTag, error)
	Query(ctx context.Context, sql string, args ...any) (pgx.Rows, error)
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
}

type Server struct {
	pool      *pgxpool.Pool
	publicURL string
	now       func() time.Time
}

func New(pool *pgxpool.Pool, publicURL string) *Server {
	return &Server{pool: pool, publicURL: strings.TrimRight(publicURL, "/"), now: time.Now}
}

// today is the current calendar date at UTC midnight, comparable with DATE columns.
func (s *Server) today() time.Time {
	n := s.now()
	return time.Date(n.Year(), n.Month(), n.Day(), 0, 0, 0, 0, time.UTC)
}

func (s *Server) Routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	})
	mux.HandleFunc("POST /api/admin/reset", s.handleReset)

	// Accounts
	mux.HandleFunc("GET /api/users", s.handleUsers)
	mux.HandleFunc("GET /api/me", s.withUser(s.handleMe))
	mux.HandleFunc("GET /api/accounts", s.withUser(s.handleAccount))

	// PIM
	mux.HandleFunc("GET /api/categories", s.handleCategories)
	mux.HandleFunc("GET /api/products", s.withUser(s.handleSearch))
	mux.HandleFunc("GET /api/products/{sku}", s.withUser(s.handleProduct))

	// OMS
	mux.HandleFunc("GET /api/network", s.withUser(s.handleNetwork))
	mux.HandleFunc("POST /api/orders/preview", s.withUser(s.handlePreview))
	mux.HandleFunc("POST /api/orders", s.withUser(s.handleCreateOrder))
	mux.HandleFunc("GET /api/orders", s.withUser(s.handleListOrders))
	mux.HandleFunc("GET /api/orders/{id}", s.withUser(s.handleGetOrder))
	mux.HandleFunc("POST /api/orders/{id}/approve", s.withUser(s.handleApprove))
	mux.HandleFunc("POST /api/orders/{id}/reject", s.withUser(s.handleReject))
	mux.HandleFunc("POST /api/orders/{id}/ship", s.withUser(s.handleShip))

	// eProcurement
	mux.HandleFunc("GET /api/punchout/identities", s.handlePunchoutIdentities)
	mux.HandleFunc("GET /api/punchout/sample", s.handlePunchoutSample)
	mux.HandleFunc("POST /api/punchout/setup", s.handlePunchoutSetup)
	mux.HandleFunc("GET /api/punchout/sessions/{id}", s.handlePunchoutSession)
	mux.HandleFunc("POST /api/punchout/sessions/{id}/return", s.handlePunchoutReturn)
	mux.HandleFunc("POST /api/edi/850", s.handleEDI850)
	mux.HandleFunc("GET /api/edi/messages", s.handleEDIMessages)

	// KeepStock
	mux.HandleFunc("GET /api/keepstock", s.withUser(s.handleKeepStock))
	mux.HandleFunc("POST /api/keepstock/bins/{id}/scan", s.withUser(s.handleBinScan))
	mux.HandleFunc("POST /api/keepstock/machines/{id}/dispense", s.withUser(s.handleDispense))
	mux.HandleFunc("POST /api/keepstock/replenish", s.withUser(s.handleReplenish))

	return logRequests(mux)
}

func (s *Server) handleReset(w http.ResponseWriter, r *http.Request) {
	if err := db.Reset(r.Context(), s.pool); err != nil {
		serverError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "reset"})
}

// ---------------------------------------------------------------- helpers

type apiError struct {
	status int
	msg    string
}

func (e *apiError) Error() string { return e.msg }

func badRequest(msg string) error { return &apiError{http.StatusBadRequest, msg} }
func notFound(msg string) error   { return &apiError{http.StatusNotFound, msg} }
func forbidden(msg string) error  { return &apiError{http.StatusForbidden, msg} }
func conflict(msg string) error   { return &apiError{http.StatusConflict, msg} }

// collect runs a query and scans every row into a T.
func collect[T any](ctx context.Context, q dbtx, scan func(pgx.Row, *T) error, sql string, args ...any) ([]T, error) {
	rows, err := q.Query(ctx, sql, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []T{}
	for rows.Next() {
		var v T
		if err := scan(rows, &v); err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	return out, rows.Err()
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, err error) {
	var ae *apiError
	if errors.As(err, &ae) {
		writeJSON(w, ae.status, map[string]string{"error": ae.msg})
		return
	}
	if errors.Is(err, pgx.ErrNoRows) {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "not found"})
		return
	}
	serverError(w, err)
}

func serverError(w http.ResponseWriter, err error) {
	log.Printf("error: %v", err)
	writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "internal error"})
}

func decode(r *http.Request, v any) error {
	dec := json.NewDecoder(http.MaxBytesReader(nil, r.Body, 1<<20))
	if err := dec.Decode(v); err != nil {
		return badRequest("invalid JSON body: " + err.Error())
	}
	return nil
}

func logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		next.ServeHTTP(w, r)
		log.Printf("%s %s %s", r.Method, r.URL.Path, time.Since(start).Round(time.Millisecond))
	})
}

type userHandler func(w http.ResponseWriter, r *http.Request, u *user)

// withUser resolves the acting persona. The mirror has no login: the UI's
// persona switcher sends X-User-ID so the same SKU can be viewed through
// different corporate accounts side by side.
func (s *Server) withUser(h userHandler) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id := r.Header.Get("X-User-ID")
		if id == "" {
			id = "u-dana"
		}
		u, err := s.loadUser(r.Context(), s.pool, id)
		if err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				writeErr(w, badRequest("unknown user "+id))
				return
			}
			writeErr(w, err)
			return
		}
		h(w, r, u)
	}
}
