# ADR-007 - Named API keys, operator role and client_id handling

**Status:** Proposed
**Date:** 2026-09-24

## Context

The API is reachable from the internet. Clients must only see their own notifications. An internal
monitoring team (A9) needs cross-tenant access. A `client_id` query parameter is useful for
operators and is a BOLA foot-gun if a client can switch tenants by sending one.

## Decision

- Keys are configured as `app.security.api-keys[]` with a **name**, the secret (from env), a role
  (`CLIENT` or `OPERATOR`), and `client_id` only for `CLIENT`. The name is what logs and the
  operator audit trail record; the secret is never logged.
- A custom `ApiKeyAuthenticationFilter` (no Spring Security) protects `/notification_events/**`.
  Comparison is constant-time. Missing and invalid keys return the same 401 `unauthorized` body.
- `Requester` is `Client(keyName, clientId)` or `Operator(keyName)`. Use cases receive it; tenant
  predicates are applied in application and SQL, not only in the controller.
- For a Client, the tenant is always `requester.clientId`. A `client_id` on the request is ignored
  (no 403). For an Operator, `client_id` is an optional filter; omitting it lists every tenant.
- Every Operator list, get and replay — including `not_found`, `not_replayable` and
  `subscription_inactive` — emits an `audit` log line (`audit_event=operator_action`). Clients do
  not.

Outbound webhook SSRF, HMAC and the DNS-rebinding TOCTOU limit are documented in
[docs/security.md](../security.md).

## Consequences

- Demo and local profiles can ship named keys with env defaults; other profiles have no keys unless
  configured, so a forgotten env var fails closed (401).
- Ignoring `client_id` for clients avoids an information leak that a 403 would create.
- The audit trail is a structured logger, not an immutable sink. That is enough to show who did
  what in the demo; production should ship the same events to a SIEM.

## Alternatives

- Spring Security: more moving parts for a single header. A later gateway/OAuth2 migration stays
  possible because `Requester` is already an application type.
- Trust `client_id` from the caller: breaks tenant isolation (API1).
- Hash keys at rest now: better for production, extra setup for a take-home whose secrets already
  live in env.
