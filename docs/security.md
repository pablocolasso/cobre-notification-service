# Security

Interpreted as **OWASP API Security Top 10 2023**, mapped to Top 10 2021 where useful (A01, A07,
A10, A05, A09). Tenant keys: [ADR-007](adr/ADR-007-api-keys-and-operator-audit.md).

## API1 Broken Object Level Authorization (2021 A01)

IDs are in the URL. A client must not learn whether another tenant's row exists.

**Mitigation:** tenant comes from the API key (`Requester`). A client's `client_id` query param is
ignored. SQL and use cases filter by tenant. Cross-tenant get/replay → **404**, not 403.

## API2 Broken Authentication (2021 A07)

The API is public.

**Mitigation:** `X-API-Key` from env, constant-time compare, same 401 body if missing or wrong.
The key is never logged; logs use the configured **name**. No keys in the default (non-local)
profile → fail closed.

## API3 Broken Object Property Level Authorization / data exposure (2021 A01)

`content` is financial. `webhook_url` can carry tokens. `signing_secret` is a credential.

**Mitigation:** `content` only in the DB, the webhook body and the owner/operator API response.
Never in logs, metrics, `last_error` or `toString`. List/detail do not expose the raw webhook URL
on the list; detail masks it to the host. Secret never in logs or errors.

## API8 Security misconfiguration (2021 A05)

**Mitigation:** management on port **8081** (not the public API port). Compose publishes 8081 for
local/demo only. `ddl-auto=validate`. Redirects disabled. HTTP/1.1 (no `Upgrade: h2c`).

## API10 Unsafe consumption of APIs — SSRF (2021 A10)

Webhook URLs are tenant-controlled.

**Mitigation:** `WebhookDestinationGuard` before every POST. Strict default: `https`, public
addresses, port 443 or `> 1023`. Allowlist for WireMock / localhost. `Redirect.NEVER`.

**Limit:** DNS rebinding (TOCTOU between the guard's resolve and `HttpClient`). Not pinned in this
scope. Presentation public HTTPS URLs do not need an allowlist;
`WEBHOOK_SSRF_ALLOWED_HOSTS` is an escape hatch.

## Injection (API Top 10 2023 API8 / classic A03)

JSON bind + typed JDBC/JPA parameters. Kafka values are not concatenated into SQL. Invalid events
go to the DLT without putting the payload in logs.

## HMAC

Optional `HMAC-SHA256(secret, timestamp + "." + body)`. Headers `X-Cobre-Timestamp` and
`X-Cobre-Signature: v1=`. Secret from the active subscription at claim. Unsigned if null.

## Public vs internal errors

| Surface | What the caller sees |
|---|---|
| API ProblemDetail | Stable `code`, short detail, `correlation_id`. No SQL, no stack, no `content`. |
| `last_error` | Sanitizer: code + service-built message, 500 chars. |
| Logs | IDs, `error_code`, `http_status`. No keys, secrets, payload or full webhook URL. |
| DLT headers | Closed `reason` / `detail`, not the exception message. |

Production follow-ups: hashed keys at rest, secret manager, egress proxy, DNS pinning, SIEM for
`audit` lines.
