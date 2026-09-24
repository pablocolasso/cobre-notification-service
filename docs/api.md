# API and webhook contract

Base URL: `http://localhost:8080`. Auth: `X-API-Key`. Optional `X-Request-Id` becomes
`correlation_id` (also returned on ProblemDetail). JSON is snake_case. Statuses are lowercase.

Swagger UI: `/swagger-ui/index.html`. Spec: `/v3/api-docs`.

There is no `delivery_date` field. Use `event_created_at`, `last_attempt_at` and `delivered_at`.

## `GET /notification_events`

Query: `delivery_status`, `created_from`, `created_to` (inclusive / exclusive ISO-8601 on
`event_created_at`), `page` (default 0), `size` (default 20, max 100), `client_id` (operator only;
ignored for client keys).

Order: `event_created_at DESC, id DESC`.

```bash
curl -s -H "X-API-Key: dev-client-001" \
  "http://localhost:8080/notification_events?delivery_status=completed&size=5"
```

```json
{
  "items": [
    {
      "notification_event_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "event_id": "EVT101",
      "client_id": "CLIENT001",
      "event_type": "credit_card_payment",
      "content": "Credit card payment received for $150.00",
      "delivery_status": "completed",
      "event_created_at": "2026-09-23T09:30:22Z",
      "last_attempt_at": "2026-09-23T09:30:23Z",
      "delivered_at": "2026-09-23T09:30:23Z",
      "attempt_count": 1,
      "next_attempt_at": null
    }
  ],
  "page": 0,
  "size": 5,
  "total_elements": 1,
  "total_pages": 1
}
```

## `GET /notification_events/{notification_event_id}`

Same tenant rules: another tenant's id is **404**, not 403. `webhook_url` is host-only.

```json
{
  "notification_event_id": "…",
  "event_id": "EVT109",
  "delivery_status": "failed",
  "webhook_url": "https://webhook-mock",
  "replay_count": 0,
  "last_error": "http_status: HTTP 500; retries exhausted after 5 attempts",
  "delivery_attempts": [
    {
      "attempt_number": 1,
      "trigger": "initial",
      "status": "retryable_failure",
      "http_status": 500,
      "error_code": "http_status",
      "started_at": "2026-09-23T17:25:31Z",
      "completed_at": "2026-09-23T17:25:31Z",
      "duration_ms": 40
    }
  ]
}
```

## `POST /notification_events/{notification_event_id}/replay`

Only `failed`. Re-resolves the active subscription URL. Resets `cycle_attempt_count`, increments
`replay_count`, leaves `attempt_count` as-is. **202** + the resource. Concurrent replays: one 202,
the rest 409.

## Errors (RFC 9457)

| HTTP | `code` |
|---|---|
| 400 | `invalid_request` |
| 401 | `unauthorized` (same body if the key is missing or wrong) |
| 404 | `notification_event_not_found` |
| 409 | `not_replayable`, `subscription_inactive` |
| 500 | `internal_error` |
| 503 | `service_unavailable` |

```json
{
  "type": "about:blank",
  "title": "Notification event not found",
  "status": 404,
  "detail": "The notification event does not exist",
  "code": "notification_event_not_found",
  "correlation_id": "corr-1"
}
```

## Outbound webhook

`POST {webhook_url}` JSON:

| Field | Source |
|---|---|
| `notification_event_id` | internal UUID |
| `event_id` | platform id |
| `event_type` | |
| `client_id` | |
| `occurred_at` | platform `occurred_at` / `event_created_at` |
| `content` | opaque string |

Headers (always):

- `Content-Type: application/json`
- `Idempotency-Key: <notification_event_id>` — **same** on retries and replays
- `X-Cobre-Event-Id`, `X-Cobre-Event-Type`, `X-Cobre-Delivery-Attempt`

If `signing_secret` is set: `X-Cobre-Timestamp` (epoch seconds) and
`X-Cobre-Signature: v1=<hex(HMAC-SHA256(secret, timestamp + "." + body))>`.

HTTP/1.1, no redirects. Receiver should treat 2xx as success and dedupe on `Idempotency-Key`.
2xx completes; 408/429/5xx retry; most 4xx and 3xx fail permanently.
