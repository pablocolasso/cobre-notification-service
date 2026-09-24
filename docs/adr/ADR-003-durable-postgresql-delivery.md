# ADR-003 - Durable PostgreSQL-based delivery and retry

**Status:** Accepted (time source). Poller, backoff and retry sections are completed with Phase 2.
**Date:** 2026-09-24

## Context

Delivery state lives in PostgreSQL. A worker claims due rows with `FOR UPDATE SKIP LOCKED`, takes a lease
(`locked_by`, `locked_until`), calls the webhook outside any transaction and records the result with a fenced
update. Several instances may run the worker at the same time.

## Decision: time source

All lifecycle timestamps are produced by the application `java.time.Clock` (`Clock.systemUTC()` in production,
fixed clocks in tests) and passed to SQL as parameters. SQL never calls `now()` in application paths; the
`DEFAULT now()` on `created_at` / `updated_at` in `V1__init.sql` exists only for manual or test inserts.

| Value | Written by | Clock of |
|---|---|---|
| `next_attempt_at` (new notification) | `IngestPlatformEventService` via `NotificationEvent.pendingFrom` | the node consuming Kafka |
| claim cutoff `next_attempt_at <= :now` | `DeliverNotificationService.deliverDueNotifications` | the worker node |
| `locked_until = now + lease` | same `now` as the claim cutoff | the worker node |
| `last_attempt_at`, `updated_at`, attempt `started_at` | same `now` as the claim | the worker node |
| `completed_at`, `delivered_at`, result `updated_at` | second `clock.instant()` after the HTTP call | the worker node |

Within a single claim the cutoff and `locked_until` come from the same `Instant`, so they are always
consistent with each other. Clocks are compared across nodes only when one node writes a timestamp and another
node compares against it.

### Assumption

All nodes run NTP-synchronised clocks with skew well below the smallest interval the design depends on:
the lease duration (60s) and the minimum retry backoff (to be defined in Phase 2).

### Risks

- **Ingest node ahead of worker node:** a new notification is delivered late by the skew (it looks "not yet
  due"). Bounded by the skew; no loss.
- **Ingest node behind worker node:** delivered early by the skew. Harmless.
- **Lease expiry across nodes (Phase 2 lease recovery):** the recovering node compares `locked_until` written
  by the claiming node against its own clock. A recovering node ahead by more than the remaining lease reclaims
  a row that is still being delivered, producing a duplicate delivery. Fencing prevents the stale result from
  overwriting the new one, and receivers dedupe with `Idempotency-Key`, but the webhook is called twice.
  Mitigation: lease (60s) much greater than the webhook timeout (5s) plus expected skew.
- **Clock steps (NTP correction, VM resume):** `duration_ms` is computed from two wall-clock readings and can be
  wrong or negative after a step.

### Alternative considered

Use the database clock for the claim cutoff, `locked_until` and lease recovery (`now()` inside the SQL, returned
with `RETURNING`). This removes cross-node skew for lease decisions, since there is a single clock. It costs
determinism in tests (a fixed `Clock` no longer controls these values) and splits time between two sources.
Rejected in the human review of Phase 1: the application `Clock` is kept.
