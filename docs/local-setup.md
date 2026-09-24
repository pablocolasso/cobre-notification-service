# Local and presentation setup

```bash
docker compose up --build
```

Profiles on the `app` service: `local,demo`. WireMock is `webhook-mock:8080` inside the network and
`localhost:8089` on the host. API: `http://localhost:8080`. Actuator: `http://localhost:8081`.

## Presentation day

A public HTTPS URL does not need an allowlist. Restart the app after setting the vars so the
subscription seed picks them up.

```bash
WEBHOOK_URL=https://url-real-del-dia \
WEBHOOK_SIGNING_SECRET=secreto-si-lo-piden \
docker compose up app
```

If the host is blocked by the SSRF guard (unlikely on a public URL; possible on a corporate
resolver), add it. The value is **merged** with the local/demo list (`webhook-mock`, `localhost`);
it does not replace it.

```bash
WEBHOOK_SSRF_ALLOWED_HOSTS=host-del-dia docker compose up app
```

Comma-separated hosts are accepted: `WEBHOOK_SSRF_ALLOWED_HOSTS=a.example,b.example`.

| Variable | Maps to | Default in compose |
|---|---|---|
| `WEBHOOK_URL` | `app.demo.webhook-url` | `http://webhook-mock:8080/webhook/ok` |
| `WEBHOOK_SIGNING_SECRET` | `app.demo.signing-secret` | empty (no HMAC headers) |
| `WEBHOOK_SSRF_ALLOWED_HOSTS` | `app.webhook.ssrf.extra-allowed-hosts` | empty |
| `WEBHOOK_FLAKY_URL` | one CLIENT002 subscription | WireMock `/webhook/flaky` |
| `WEBHOOK_FAILING_URL` | one CLIENT003 subscription | WireMock `/webhook/error` |
| `CLIENT001_API_KEY` / `CLIENT002_API_KEY` / `CLIENT003_API_KEY` / `OPS_API_KEY` | named API keys | `dev-client-001`, …, `dev-ops` |

Header: `X-API-Key`.

## Host-run (no compose app)

`SPRING_PROFILES_ACTIVE=local,demo`, Postgres on `:5432`, Kafka on `:9092`, WireMock on `:8089`.
Default `WEBHOOK_URL` in the demo profile is `http://localhost:8089/webhook/ok`.
