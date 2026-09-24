# Outbound and API security

This note covers the Phase 4 outbound controls and the known limits. Tenant isolation and API keys
are in [ADR-007](adr/ADR-007-api-keys-and-operator-audit.md).

## Webhook destination (SSRF)

`WebhookDestinationGuard` runs in `JdkWebhookClient` **before** any HTTP call.

| Mode | Config | Behaviour |
|---|---|---|
| Strict (default) | `app.webhook.ssrf.strict=true` | `https` only; port 443 or `> 1023` (plus `extra-allowed-ports`); DNS resolve and reject loopback, link-local, RFC 1918, any-local, multicast, CGNAT `100.64.0.0/10`, IPv6 ULA `fc00::/7`. |
| Permissive | `app.webhook.ssrf.strict=false` | Parseable `http`/`https` only. No DNS or IP checks. One WARN per URL, with scheme and host only. |
| Allowlist | `app.webhook.ssrf.allowed-hosts` | Hosts in the list skip the strict IP, port and `https`-only rules. Scheme must still be `http` or `https`. |

Production default: empty allowlist. Local and demo profiles allow `webhook-mock` (compose WireMock)
plus `localhost` / `127.0.0.1` so a host-run demo against the published WireMock port still works.

A rejected URL becomes `DeliveryResult.InvalidDestination` → `FAILED` with
`error_code = invalid_destination`. No request is sent. Redirects stay `HttpClient.Redirect.NEVER`
(Phase 1/2) so a 3xx cannot bounce into an internal address.

The presentation webhook is expected to be public `https`. Setting
`WEBHOOK_URL=https://… docker compose up app` does **not** need an allowlist entry.

### Known limitation: DNS rebinding (TOCTOU)

The guard resolves the host, then `HttpClient` resolves it again when it connects. A resolver that
answers with a public address first and a private one a moment later can bypass the check. Pinning
the connected address is out of this scope. Mitigations if this service is exposed to untrusted
subscription URLs: resolve once and connect to the validated address, or put an egress proxy in
front of outbound webhooks.

## HMAC webhook signature

`subscriptions.signing_secret` is nullable (`VARCHAR(256)`, Flyway `V2`). When it is null or blank,
the client sends no signature headers (clean demo against WireMock). When it is set:

- `X-Cobre-Timestamp`: epoch seconds from the injected `Clock`
- `X-Cobre-Signature`: `v1=` + hex(`HMAC-SHA256(secret, timestamp + "." + body)`)

The body is the exact JSON bytes about to be posted.

**Secret source:** the **active** subscription at claim time
(`RETURNING (SELECT s.signing_secret FROM subscriptions s WHERE … AND s.active LIMIT 1)`).
It is not snapshotted onto `notification_events`. A rotation applies to the next claim. The
webhook URL remains the snapshot taken at ingest. If the subscription is inactive at claim, the
secret is null and the request is unsigned.

The secret is never written to logs, metrics, `last_error`, or `toString` of `Subscription`,
`DeliveryTask` or the claim row.

Demo seed: add `signing-secret` under `app.demo.subscriptions[]` when the presentation endpoint
expects a signature.

## HTTP/1.1

`HttpClient` is built with `Version.HTTP_1_1` and does not send `Upgrade: h2c`. That avoids a
cleartext protocol upgrade on the local WireMock path.

## Sensitive data

`content` and `signing_secret` stay out of logs, error text and metric tags. Error messages use
closed reason codes from the guard (`blocked_address`, `https_required`, …).
