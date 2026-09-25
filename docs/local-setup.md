# Local and presentation setup

```bash
docker compose up --build --wait
```

Profiles on the `app` service: `local,demo`. WireMock is `webhook-mock:8080` inside the network and
`localhost:8089` on the host. API: `http://localhost:8080`. Management (health, metrics,
prometheus): `http://localhost:8081`. Do not publish 8081 on a public network.

## Inject events

Prefer the scripts (they read `demo/platform-events.jsonl`). **Save the file** before running;
`event_id` must be new or ingest is a duplicate.

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\publish-events.ps1
```

```bash
./scripts/publish-events.sh
```

On Windows PowerShell, `< demo/platform-events.jsonl` into `docker compose exec` often does not
send the file; use the `.ps1` (with `-ExecutionPolicy Bypass`) or Git Bash.

## Postman

- API (8080): [demo/Cobre-Notification-Service.postman_collection.json](../demo/Cobre-Notification-Service.postman_collection.json) — run folders 00–05, publish, then 06.
- Actuator (8081): [demo/Cobre-Notification-Observability.postman_collection.json](../demo/Cobre-Notification-Observability.postman_collection.json).

Keep collection Authorization on **No Auth**. See [README](../README.md).

## Presentation day

A public HTTPS URL does not need an allowlist. Restart the app after setting the vars so the
subscription seed upserts `webhook_url` and `signing_secret`. An empty
`WEBHOOK_SIGNING_SECRET` stores SQL `NULL` (HMAC off, not a masked value).

**Windows (PowerShell / Cursor):**

```powershell
$env:WEBHOOK_URL = "https://url-real-del-dia"
$env:WEBHOOK_FLAKY_URL = $env:WEBHOOK_URL
$env:WEBHOOK_FAILING_URL = $env:WEBHOOK_URL
$env:WEBHOOK_SIGNING_SECRET = "secreto-si-lo-piden"
docker compose up --build app
```

**macOS / Linux / Git Bash:**

```bash
WEBHOOK_URL=https://url-real-del-dia \
WEBHOOK_FLAKY_URL=https://url-real-del-dia \
WEBHOOK_FAILING_URL=https://url-real-del-dia \
WEBHOOK_SIGNING_SECRET=secreto-si-lo-piden \
docker compose up --build app
```

If the host is blocked by the SSRF guard (unlikely on a public URL; possible on a corporate
resolver), add it. The value is **merged** with the local/demo list (`webhook-mock`, `localhost`);
it does not replace it.

```powershell
$env:WEBHOOK_SSRF_ALLOWED_HOSTS = "host-del-dia"
docker compose up --build app
```

```bash
WEBHOOK_SSRF_ALLOWED_HOSTS=host-del-dia docker compose up --build app
```

Comma-separated hosts are accepted: `WEBHOOK_SSRF_ALLOWED_HOSTS=a.example,b.example`.

| Variable | Maps to | Default in compose |
|---|---|---|
| `WEBHOOK_URL` | `app.demo.webhook-url` | `http://webhook-mock:8080/webhook/ok` |
| `WEBHOOK_SIGNING_SECRET` | `app.demo.signing-secret` | empty (no HMAC headers) |
| `WEBHOOK_SSRF_ALLOWED_HOSTS` | `app.webhook.ssrf.extra-allowed-hosts` | `webhook-mock,localhost` (merged with the demo allowlist) |
| `WEBHOOK_FLAKY_URL` | one CLIENT002 subscription | WireMock `/webhook/flaky` |
| `WEBHOOK_FAILING_URL` | one CLIENT003 subscription | WireMock `/webhook/error` |
| `APP_DELIVERY_WORKER_POLL_INTERVAL` | `app.delivery.worker.poll-interval` | `500ms` (`fixedDelay` between ticks) |
| `APP_DELIVERY_WORKER_MAX_CONCURRENCY` | `app.delivery.worker.max-concurrency` | `20` |
| `APP_DELIVERY_RETRY_MAX_ATTEMPTS` | `app.delivery.retry.max-attempts` | `5` (per cycle; replay resets the budget) |
| `APP_DELIVERY_RETRY_BASE_DELAY` | `app.delivery.retry.base-delay` | `2s` (demo; `application.yml` default is `5s`) |
| `APP_DELIVERY_RETRY_MAX_DELAY` | `app.delivery.retry.max-delay` | `30s` (demo; `application.yml` default is `10m`) |
| `CLIENT001_API_KEY` / `CLIENT002_API_KEY` / `CLIENT003_API_KEY` / `OPS_API_KEY` | named API keys | `dev-client-001`, …, `dev-ops` |

Header: `X-API-Key`.

## Host-run (no compose app)

`SPRING_PROFILES_ACTIVE=local,demo`, Postgres on `:5432`, Kafka on `:9092`, WireMock on `:8089`.
Default `WEBHOOK_URL` in the demo profile is `http://localhost:8089/webhook/ok`.
