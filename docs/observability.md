# Observability

Management port: `8081` (`MANAGEMENT_PORT`). Compose publishes it for local/demo only. Do not expose
that port on a public network.

| Endpoint | Purpose |
|---|---|
| `/actuator/health` | Aggregate health |
| `/actuator/health/liveness` | Process is up (`livenessState`) |
| `/actuator/health/readiness` | Ready for traffic (`readinessState` + DB; Kafka when the indicator is present) |
| `/actuator/metrics` | Micrometer names |
| `/actuator/prometheus` | Prometheus scrape |

## Metrics

No `client_id` tags. Names:

| Name | Type | Tags |
|---|---|---|
| `notification.events.received` | counter | `outcome`: accepted, duplicate, no_subscription, invalid, error |
| `notification.created` | counter | `event_type` |
| `notification.delivery.attempts` | counter | `outcome`: success, retryable_failure, permanent_failure, abandoned; `event_type` |
| `notification.delivery.duration` | timer | `outcome` (same closed set as attempts) |
| `notification.retries.scheduled` | counter | — |
| `notification.failed` | counter | `reason`: permanent_error, max_attempts_reached, invalid_destination |
| `notification.replays` | counter | `outcome`: accepted, not_replayable, not_found |
| `notification.processing.latency` | timer | COMPLETED only (`event_created_at` → `delivered_at`) |
| `notification.dlt.published` | counter | `reason`: invalid_event, unexpected_error |
| `notification.backlog` | gauge | `status`: pending, retrying, processing |
| `notification.backlog.oldest.age.seconds` | gauge | oldest PENDING or RETRYING `created_at` |

Backlog gauges query PostgreSQL every 15s (`BacklogMetricsBinder`), not the worker poll.

Consumer lag is Spring Kafka / Micrometer: `kafka.consumer.fetch.manager.records.lag.max`.

## Logging

Local profile: `logging.structured.format.console=ecs`. Other profiles keep a text pattern with
`%kvp` and `%mdc`.

MDC: `correlation_id` (`X-Request-Id` or generated; Kafka header `correlation-id` or generated),
`notification_event_id`, `event_id`, `client_id`, `attempt_number`, `status`, plus `caller` on HTTP.
API `ProblemDetail` includes `correlation_id`.

Never logged: `content`, signing secrets, API keys, full `webhook_url`.

## Suggested alerts (not implemented)

- `notification.backlog{status=retrying}` rising for more than 10 minutes.
- `notification.backlog.oldest.age.seconds` > 300.
- Rate of `notification.delivery.attempts{outcome=permanent_failure}` above an agreed X% of attempts
  in 5 minutes (pick X from a quiet week; start at 5%).
- `kafka.consumer.fetch.manager.records.lag.max` above the usual peak for more than 5 minutes.
- `notification.dlt.published` > 0 (page on any dead-letter).
