# Cobre Notification Service

Kafka platform events become HTTPS webhooks, with durable retries in PostgreSQL and a tenant-scoped
self-service API. Hexagonal layout, Java 21, Spring Boot 4.1.

**In:** `platform.events.v1` (JSON, key = `client_id`). **Out:** `POST` to the client's webhook
(`Idempotency-Key`, optional HMAC). **State:** PostgreSQL (`SKIP LOCKED` + lease). **API:**
`GET/POST /notification_events` behind `X-API-Key`.

| Port | What |
|---|---|
| `8080` | API + Swagger (`/swagger-ui/index.html`, `/v3/api-docs`) |
| `8081` | Actuator (health, metrics, prometheus). Not for a public network. |
| `8089` | WireMock (host). Inside compose: `webhook-mock:8080`. |
| `5432` | PostgreSQL (`notifications` / `notifications`) |
| `9092` | Kafka (host) |

## Reviewer path

1. [Quick start](#quick-start) and [inject events](#inject-events).
2. Import Postman or use curl ([try the API](#try-the-api)).
3. Architecture and contract: [docs/architecture.md](docs/architecture.md), [docs/api.md](docs/api.md).
4. Why we chose X: [docs/adr/](docs/adr/). Assumptions: [docs/assumptions.md](docs/assumptions.md).
5. Security / ops: [docs/security.md](docs/security.md), [docs/observability.md](docs/observability.md).
6. How the machine was built: [docs/ai-log.md](docs/ai-log.md). Plan: [docs/plan-v2.md](docs/plan-v2.md).

After a code change, rebuild the image: `docker compose up --build --wait`. A running container
does not pick up Java changes.

## Quick start

```bash
docker compose up --build --wait
```

Profiles on `app`: `local,demo`. On boot the demo seeder upserts subscriptions and loads the
fixture `EVT001`–`EVT010` (historical rows, `origin=FIXTURE`). Kafka is **not** required to list
those.

### API keys (`X-API-Key`)

| Key | Role | Sees |
|---|---|---|
| `dev-client-001` | CLIENT | CLIENT001 only |
| `dev-client-002` | CLIENT | CLIENT002 only |
| `dev-client-003` | CLIENT | CLIENT003 only |
| `dev-ops` | OPERATOR | all tenants; `?client_id=` is an optional filter |

For a **client** key, `?client_id=` is **ignored** (200, own tenant). Cross-tenant get/replay →
**404**, not 403.

## Inject events

Lines are `client_id|{json}`. The JSON must include `event_id`, `event_type`, `client_id`,
`occurred_at`, `content`, `schema_version`. **`event_id` is globally unique**; republishing the
same id is a no-op. To run the file again, change the ids (e.g. `BEVT101`) and **save the file**
before publishing — the script reads disk, not an unsaved editor buffer.

`event_created_at` in the API is **`occurred_at` from the payload** (when the business event
happened), not ingest time. `last_attempt_at` / `delivered_at` are when the worker called the
webhook.

**Windows (PowerShell ExecutionPolicy often blocks `.ps1`):**

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\publish-events.ps1
```

**macOS / Linux / Git Bash:**

```bash
./scripts/publish-events.sh
```

Optional: `.\scripts\publish-events.ps1 -File demo\platform-events.jsonl -Topic platform.events.v1`

Raw compose (Git Bash / WSL; `<` file redirect is unreliable in Windows PowerShell):

```bash
docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka:29092 --topic platform.events.v1 \
  --property parse.key=true --property key.separator='|' \
  < demo/platform-events.jsonl
```

### What each demo line does

Routing is **subscription `(client_id, event_type)` → webhook URL**, not a WireMock rule on the
body.

| Events | Destination | Expected |
|---|---|---|
| Most types | `/webhook/ok` | `completed` |
| CLIENT002 `debit_purchase` (`EVT108`) | `/webhook/flaky` | 500, 500, then 200 → `retrying` then `completed` |
| CLIENT003 `credit_cashback` (`EVT109`) | `/webhook/error` | 500 until budget (5) → `failed` |
| `EVT111` `loan_disbursement` | no subscription | not inserted |
| Trailing duplicate `EVT101` | — | ingest `duplicate` |

## Try the API

### Postman

Import **both** collections (Authorization = **No Auth**; each request sets `X-API-Key`):

| File | Port | Order |
|---|---|---|
| [demo/Cobre-Notification-Service.postman_collection.json](demo/Cobre-Notification-Service.postman_collection.json) | 8080 | folders **00 → 05** (fixture, no Kafka), then publish jsonl, then **06** |
| [demo/Cobre-Notification-Observability.postman_collection.json](demo/Cobre-Notification-Observability.postman_collection.json) | 8081 | health → metrics → prometheus |

If “list without API key” returns **200**, the `app` image is stale: rebuild.

### Curl (after compose is up)

```bash
curl -s -H "X-API-Key: dev-client-001" "http://localhost:8080/notification_events?size=20"
curl -s -H "X-API-Key: dev-ops" \
  "http://localhost:8080/notification_events?client_id=CLIENT003&size=20"
curl -s -H "X-API-Key: dev-ops" \
  "http://localhost:8080/notification_events/{notification_event_id}"
curl -s -X POST -H "X-API-Key: dev-ops" \
  "http://localhost:8080/notification_events/{notification_event_id}/replay"
```

Replay: **202** + new cycle only if status is `failed`. Otherwise **409**. OpenAPI:
`http://localhost:8080/swagger-ui/index.html`. Prometheus:
`http://localhost:8081/actuator/prometheus` (`notification_*`).

Contract, filters and webhook headers: [docs/api.md](docs/api.md).

## Presentation day

HMAC is optional. Default `WEBHOOK_SIGNING_SECRET` is empty → `subscriptions.signing_secret` is
**NULL** (not hidden). Set the env and **restart** `app` so the seeder upserts URL and secret.

**Windows (PowerShell / Cursor):** `VAR=value \` is bash and will fail here.

```powershell
$env:WEBHOOK_URL = "https://url-real"
$env:WEBHOOK_FLAKY_URL = $env:WEBHOOK_URL
$env:WEBHOOK_FAILING_URL = $env:WEBHOOK_URL
$env:WEBHOOK_SIGNING_SECRET = "secreto-si-lo-piden"
# only if last_error is invalid_destination:...
$env:WEBHOOK_SSRF_ALLOWED_HOSTS = "hostname-sin-https"
docker compose up --build app
```

**macOS / Linux / Git Bash:**

```bash
WEBHOOK_URL=https://url-real \
WEBHOOK_FLAKY_URL=https://url-real \
WEBHOOK_FAILING_URL=https://url-real \
WEBHOOK_SIGNING_SECRET=secreto-si-lo-piden \
WEBHOOK_SSRF_ALLOWED_HOSTS=hostname-sin-https \
docker compose up --build app
```

`WEBHOOK_SSRF_ALLOWED_HOSTS` only if SSRF rejects the URL. Full env table:
[docs/local-setup.md](docs/local-setup.md).

## Docs

- [Architecture](docs/architecture.md)
- [API and webhook contract](docs/api.md)
- [Local / presentation setup](docs/local-setup.md)
- [Security (OWASP)](docs/security.md)
- [Observability](docs/observability.md)
- [AI log](docs/ai-log.md)
- [Assumptions](docs/assumptions.md)
- ADRs in [docs/adr/](docs/adr/)
