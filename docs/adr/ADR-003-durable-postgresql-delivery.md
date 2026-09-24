# ADR-003 - Durable PostgreSQL-based delivery and retry

**Status:** Accepted (time source reviewed in Phase 1; claim, fencing, lease recovery and retry reviewed in
Phase 2).
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
| `completed_at`, `delivered_at`, result `updated_at`, `next_attempt_at` (retry) | second `clock.instant()` after the HTTP call | the worker node |
| lease-recovery cutoff `locked_until < :now`, recovered `next_attempt_at` | `RecoverExpiredLeasesService` | the recovering node |
| attempt `duration_ms` | `System.nanoTime` around the HTTP call (monotonic, not the `Clock`) | the worker node |

Within a single claim the cutoff and `locked_until` come from the same `Instant`, so they are always
consistent with each other. Clocks are compared across nodes only when one node writes a timestamp and another
node compares against it.

### Assumption

All nodes run NTP-synchronised clocks with skew well below the smallest interval the design depends on:
the lease duration (60s). Retry delays use full jitter, so an individual delay can be close to zero; skew there
only shifts a retry by the skew, it never duplicates or loses one.

### Risks

- **Ingest node ahead of worker node:** a new notification is delivered late by the skew (it looks "not yet
  due"). Bounded by the skew; no loss.
- **Ingest node behind worker node:** delivered early by the skew. Harmless.
- **Lease expiry across nodes:** the recovering node compares `locked_until` written
  by the claiming node against its own clock. A recovering node ahead by more than the remaining lease reclaims
  a row that is still being delivered, producing a duplicate delivery. Fencing prevents the stale result from
  overwriting the new one, and receivers dedupe with `Idempotency-Key`, but the webhook is called twice.
  Mitigation: lease (60s) much greater than the webhook timeout (5s) plus expected skew.
- **Clock steps (NTP correction, VM resume):** shift `completed_at` and retry schedules by the step.
  `duration_ms` is not affected because it uses `System.nanoTime`.

### Alternative considered

Use the database clock for the claim cutoff, `locked_until` and lease recovery (`now()` inside the SQL, returned
with `RETURNING`). This removes cross-node skew for lease decisions, since there is a single clock. It costs
determinism in tests (a fixed `Clock` no longer controls these values) and splits time between two sources.
Rejected in the human review of Phase 1: the application `Clock` is kept.

## Decision: claim and bounded concurrency

- Each worker tick first recovers expired leases, then claims. Each step runs in its own short transaction, and
  the HTTP call happens outside any transaction.
- `DeliverNotificationService` holds a `Semaphore(max-concurrency)`. A tick drains the free permits, claims at
  most that many rows, returns the unused permits right away and releases one permit per task in `finally`.
  The claim size therefore follows the free capacity, and in-flight deliveries per instance never exceed
  `app.delivery.worker.max-concurrency`.
- Deliveries run on a virtual-thread-per-task executor. The semaphore is the bound; the executor adds none.
- Claim: `UPDATE ... WHERE id IN (SELECT ... ORDER BY next_attempt_at, id LIMIT :limit FOR UPDATE SKIP LOCKED)
  RETURNING`. The rows returned by `RETURNING` are unordered; only the selection is ordered. The same
  transaction inserts one `IN_PROGRESS` attempt per row, with ids generated in the application
  (`IdGenerator`). `attempt_trigger` is `RETRY` when `cycle_attempt_count > 1`, otherwise `INITIAL` (or `REPLAY`
  for the first attempt of a replay cycle).
- Covered by `ConcurrentClaimTest`: overlapping claims partition the rows, and a claim skips rows locked by an
  open transaction without waiting. `ConcurrentWorkersTest` covers two full workers delivering each
  notification exactly once.

## Decision: fencing

`recordResult` updates the row only when all of these match: `id`, `delivery_status = 'PROCESSING'`,
`locked_by = :workerId` and `attempt_count = :attemptNumber`. `attempt_count` works as a fencing token because
every claim increments it. So a result from an older lease is rejected even when the same worker id reclaimed
the row after lease recovery (`lateResultFromAStaleLeaseDoesNotOverwriteTheRetryEvenFromTheSameWorker`). A
rejected result is logged as WARN, "Lease lost before recording the result", and the attempt keeps its
`ABANDONED` status.

## Decision: lease recovery

- `RecoverExpiredLeasesService` reads up to `recovery-batch-size` rows with `PROCESSING` and
  `locked_until < now`.
- For each row, the lifecycle decides:
  - **RETRYING** with a backoff when the cycle budget allows another attempt;
  - **FAILED** with `last_error = "lease_expired: ...; retries exhausted after N attempts"` when it does not.
- The update is fenced on the values it read (`locked_by`, `attempt_count`, `locked_until < :now`). If two
  nodes race, or the owner records its result first, the update is a no-op.
- In the same transaction, the `IN_PROGRESS` attempt becomes `ABANDONED` with `error_code = lease_expired`.
- A worker that crashes, or fails to record its result, is therefore retried or failed. Nothing stays in
  `PROCESSING` forever.
- **Accepted cost:** a delivery that is slower than the lease can reach the receiver twice. Receivers dedupe
  with `Idempotency-Key`.

## Decision: retry policy

- `HttpStatusDeliveryResultClassifier` classifies each result:
  - **success:** 2xx;
  - **retryable:** 408, 429, 5xx, and transport failures (timeout, connection errors);
  - **permanent:** any other status (including 3xx, since redirects are not followed) and invalid destinations.
- `ExponentialBackoffRetryPolicy` uses full jitter: `delay = random[0, min(max-delay, base-delay * 2^(n-1))]`.
- On 429 with `Retry-After` in seconds, the delay is at least `min(Retry-After, max-delay)`.
- `RandomGenerator` and `Clock` are injected, so tests use fixed values.
- Defaults: `base-delay` 5s, `max-delay` 10m, `max-attempts` 5 (`app.delivery.retry.*`). The demo profile uses
  2s / 30s.
- The budget counts attempts in the current cycle (`cycle_attempt_count`). A manual replay (Phase 3) starts a
  new cycle, while `attempt_count` stays the lifetime total.
- When the budget is exhausted, the notification becomes `FAILED` and keeps the last error with the suffix
  "; retries exhausted after N attempts".
