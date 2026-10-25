# MRO Mirror

A working mirror of an enterprise B2B MRO (maintenance, repair and operations) distribution platform, built from
[`ecom-system-plan.md`](ecom-system-plan.md) to study how an industrial distributor's software actually behaves.
All customers, SKUs, prices, contracts and stock levels are synthetic.

## Run it

```bash
cp .env.example .env                # then set DB_PASSWORD (required), e.g. openssl rand -hex 24
docker network inspect tunnel-net >/dev/null 2>&1 || docker network create tunnel-net   # once
docker compose up -d --build        # Postgres + Spring Boot API + React app
open http://localhost:8090          # WEB_PORT overrides the port (bound to 127.0.0.1 only)
```

The schema and seed data load automatically on first start. **Reset demo data** in the footer restores the seed.
It needs the admin token: set `ADMIN_TOKEN` in `.env` and enter it when the button asks. With `ADMIN_TOKEN`
empty (the default) the reset endpoint is switched off and answers 404.

There is no login. The persona switcher in the header sends `X-User-ID`, so you can look at the same SKU,
cart or approval queue as different people in different companies.

## Deploy to prod (Cloudflare tunnel)

`./pipeline-run.sh` (git-ignored, lives only on the server) deploys the site the same way as the other labs:

1. Creates `.env` from [`.env.example`](.env.example) if it is missing (`chmod 600`) and generates a random
   `DB_PASSWORD` when it is empty and no `pgdata` volume exists yet.
2. Reads `TUNNEL_TOKEN` from the server's global environment (e.g. `/etc/environment`), falling back to that
   one line of `.env`.
3. Runs `git-pull-project.sh` (skips the pull when the branch has no upstream).
4. **Removes every container on the host**: only one app is live behind the tunnel at a time.
5. Starts `cloudflared-tunnel` on the `tunnel-net` network, then `docker compose up -d --build`.

The tunnel routes to `http://rixus-frontend:80`, which is the `web` container. Set `PUBLIC_URL` in `.env` to the
public hostname so PunchOut StartPage and cart-return URLs point at the live site rather than localhost. The
`pgdata` volume survives redeploys and keeps the password it was created with.

## Security

The site is public through the tunnel and has no login, so:

- `POST /api/admin/reset` wipes every table, so it requires `X-Admin-Token` to match `ADMIN_TOKEN` (compared in
  constant time) and is off when `ADMIN_TOKEN` is empty.
- The web port is published on `127.0.0.1` only. Docker's iptables rules bypass `ufw`, so a bare `8090:80` would
  serve the app on the server's public IP and skip Cloudflare.
- `DB_PASSWORD` has no fallback in `docker-compose.yml`: a missing `.env` stops `docker compose` instead of starting
  Postgres with a password from this repo.
- Postgres sits only on `backend-net` (`internal: true`, not published). The public nginx container is on
  `frontend-net` and `tunnel-net` and cannot reach the database; only the API is on both networks.

## Stack

| Layer    | Tech                                                    | Notes |
|----------|---------------------------------------------------------|-------|
| API      | Java 21, Spring Boot 3.5 (Web MVC, JDBC), Postgres JDBC | One app; each bounded context owns its own Postgres schema |
| Database | Postgres 16                                             | Schemas `pim`, `accounts`, `pricing`, `oms`, `procurement`, `keepstock` |
| Web      | React 19, Vite, TypeScript, Tailwind 4, react-router 7  | Served by nginx, which proxies `/api` to the API |

The plan's tech-stack section was cut off. The API was first written in Go to mirror the Rixus project and has since been
ported to Spring Boot with the same endpoints, JSON shapes and business rules; the Go version is in the git history.

## How the five intricacies map to code

| # | Intricacy | Where | What to try |
|---|-----------|-------|-------------|
| 1 | Technical catalog & cross-referencing | `pim` schema, `api/CatalogController.java` | Each leaf category has a JSON attribute schema that drives disjunctive facets. Search competitor, OEM, obsolete or UPC numbers (`6205LLU`, `2N864`, `91257A626`). |
| 2 | Hierarchical accounts & governance | `accounts` schema, `governance/Governance.java` | Per-order and monthly limits, certification-gated categories, blanket PO balance and validity, approval chain with escalation, and state tax exemptions (with an expired one). |
| 3 | Contract pricing & eProcurement | `pricing`, `cxml`, `edi` packages, `api/EProcurementController.java` | The best of list, contract, category discount and volume tier wins, with a full trace. cXML PunchOut setup → shop → PunchOutOrderMessage. X12 850 in → order → 855 out. |
| 4 | Multi-node ATP & split routing | `oms` schema, `sourcing/Sourcing.java`, `api/Checkout.java` | DCs, branches (will-call, 60 mi courier) and suppliers. Hazmat goes ground only from licensed nodes; drop-ship-only items come from suppliers. Ship-complete vs ship-available, reservations and backorders. |
| 5 | KeepStock VMI | `keepstock` schema, `api/KeepStockController.java` | Bin cycle counts and badge-swipe vending with daily limits. Reaching min or par raises a replenishment order; shipping it refills the bin or slot. |

The **Study Guide** page (home) links a reproducible scenario for each one.

## Checkout pipeline

Java packages live under `backend/src/main/java/com/example/mromirror/`.

Every channel (web, PunchOut, EDI, KeepStock) goes through `Checkout.evaluate` in `api/Checkout.java`:

1. **Price** each line from the buyer corporation's price book (`Pricing.resolve`).
2. **Tax** at the ship-to state rate, unless the corporation holds a current exemption certificate.
3. **Govern** with `Governance.evaluate`. EDI orders skip spend limits (the ERP already approved them). KeepStock skips limits and restrictions (the program pre-authorizes them).
4. **Source** against live ATP (`Sourcing.build`).

Placing the order persists it. Auto-approved orders are **released** right away: sourcing is re-planned against
`SELECT … FOR UPDATE` inventory, stock is reserved, shipments are written and the blanket PO is drawn down.
Other orders wait for their approver. An approver whose authority is too low escalates the order up the chain.

## API (selected)

| Method | Path | Purpose |
|--------|------|---------|
| GET  | `/api/products?q=&category=&f.<attr>=` | Search with facets and cross-reference resolution |
| GET  | `/api/products/{sku}?qty=` | Specs, price trace, ATP by node, price by customer, equivalents |
| POST | `/api/orders/preview` · `/api/orders` | Run or commit the checkout pipeline |
| POST | `/api/orders/{id}/approve` · `/reject` · `/ship` | Approval workflow and warehouse simulation |
| POST | `/api/punchout/setup` | cXML PunchOutSetupRequest → StartPage URL |
| POST | `/api/punchout/sessions/{id}/return` | Cart → PunchOutOrderMessage (+ the 850 the ERP would send) |
| POST | `/api/edi/850` | X12 850 → order + 855 acknowledgment |
| POST | `/api/keepstock/bins/{id}/scan` · `/machines/{id}/dispense` · `/replenish` | VMI events |

## Tests

The domain packages (pricing, governance, sourcing, cxml, edi) are pure and unit-tested with JUnit 5 (Java, Maven and Node
do not need to be installed locally):

```bash
docker run --rm -v "$PWD/backend":/src -w /src maven:3.9.8-eclipse-temurin-21 mvn -B -q test
docker run --rm -v "$PWD/frontend":/app -w /app node:22-alpine sh -c "npm ci && npm run build"
```

## Deliberate simplifications

- Search scans Postgres and facets are computed in memory. Production would use a search engine (Elasticsearch/Solr) over 1.5M+ SKUs.
- Money is `double` rounded to cents. Production should use integer cents or decimal types.
- Single Spring Boot app, modular monolith: each context has its own schema and code package, but they share one process and transaction.
- Shipping an order is simulated in one step, and backordered quantities are treated as arriving with it.
- The PunchOut sample endpoint returns the shared secret so the simulator can play the ERP. A real system never would.
