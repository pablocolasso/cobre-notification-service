# Cobre Notification Service

Kafka platform events become HTTPS webhooks, with durable retries in PostgreSQL and a tenant-scoped
self-service API. Hexagonal layout, Java 21, Spring Boot 4.1.

**In:** `platform.events.v1` (JSON). **Out:** `POST` to the client's webhook (`Idempotency-Key`,
optional HMAC). **State:** PostgreSQL (`SKIP LOCKED` + lease). **API:**
`GET/POST /notification_events` behind `X-API-Key`.

## Quick start

```bash
docker compose up --build --wait
```

API `http://localhost:8080`, Actuator `http://localhost:8081`, WireMock `http://localhost:8089`.
Default keys: `dev-client-001` (CLIENT001), `dev-ops` (operator).

Publish the demo events (key `|` payload):

```bash
docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka:29092 --topic platform.events.v1 \
  --property parse.key=true --property key.separator='|' \
  < demo/platform-events.jsonl
```

Most lines complete against WireMock `/webhook/ok`. `EVT108` (CLIENT002 `debit_purchase`) is flaky
(500, 500, 200). `EVT109` (CLIENT003 `credit_cashback`) fails after retries. `EVT111` has no
subscription and is skipped. The duplicate `EVT101` at the end is a no-op.

Presentation URL (public HTTPS, no allowlist needed):

```bash
WEBHOOK_URL=https://url-real \
WEBHOOK_SIGNING_SECRET=secreto-si-lo-piden \
docker compose up app
```

If SSRF blocks the host: `WEBHOOK_SSRF_ALLOWED_HOSTS=host-del-dia`. Details in
[docs/local-setup.md](docs/local-setup.md).

## Demo script

1. `docker compose up --build --wait` and publish `demo/platform-events.jsonl` as above.
2. List CLIENT001 (seeded fixture + new Kafka rows):

   ```bash
   curl -s -H "X-API-Key: dev-client-001" "http://localhost:8080/notification_events?size=20"
   ```

3. Watch a live delivery: find `EVT101` / `EVT108` / `EVT109` by `event_id` (operator sees every tenant):

   ```bash
   curl -s -H "X-API-Key: dev-ops" \
     "http://localhost:8080/notification_events?client_id=CLIENT003&size=20"
   ```

   `EVT108` goes RETRYING → COMPLETED. `EVT109` ends FAILED.

4. Detail (attempts, masked webhook host):

   ```bash
   curl -s -H "X-API-Key: dev-ops" \
     "http://localhost:8080/notification_events/{notification_event_id}"
   ```

5. Replay a FAILED row (`EVT109` after retries, or a fixture `failed`):

   ```bash
   curl -s -X POST -H "X-API-Key: dev-ops" \
     "http://localhost:8080/notification_events/{notification_event_id}/replay"
   ```

   202 queues a new cycle (`trigger=REPLAY`). 409 if it is not FAILED or the subscription is gone.

6. Metrics: `http://localhost:8081/actuator/prometheus` (look for `notification_`).
   OpenAPI: `http://localhost:8080/swagger-ui/index.html`.

## Docs

- [Architecture](docs/architecture.md) — C4, sequences, states, ER
- [API and webhook contract](docs/api.md)
- [Local / presentation setup](docs/local-setup.md)
- [Security (OWASP)](docs/security.md)
- [Observability](docs/observability.md)
- [AI log](docs/ai-log.md)
- [Assumptions](docs/assumptions.md)
- ADRs in [docs/adr/](docs/adr/)
