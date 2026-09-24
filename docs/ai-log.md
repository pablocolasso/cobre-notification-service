# AI-Assisted Development Log

This log records the relevant interactions with AI assistants during the challenge. The goal is to show
engineering judgment: what was asked, what the AI proposed, what was reviewed, and what was decided.
Trivial autocompletions are not recorded.

Tooling: Cursor (agent mode, plan mode) with Claude.

Entry format: goal, prompt (summary + key excerpts), relevant AI output, human analysis, decision, resulting change.

---

## Entry 1 - Requirements analysis and implementation plan (v1 -> v2)

**Date:** 2026-09-23
**Type:** requirements analysis, architecture exploration, implementation planning

### Goal

Analyze the challenge before writing any code: separate explicit requirements from assumptions, surface
ambiguities and risks, and produce an execution-oriented plan with phases and a Definition of Done each.

### Prompt (summary)

A structured prompt (36 sections) asking for a read-only analysis in plan mode. Key constraints given to the AI:

- Sources of truth: the challenge PDF and `notification_events.json`; do not invent requirements.
- No file changes during analysis.
- Time budget of ~2.5 days; walking skeleton first
  (`Platform Event -> Kafka -> Subscription Validation -> PostgreSQL -> Webhook Delivery -> REST API`).
- Hexagonal architecture; Kafka for ingestion only; durable retries in PostgreSQL; no HTTP inside the consumer.
- `SELECT ... FOR UPDATE SKIP LOCKED` + lease for concurrent workers; at-least-once, never claim exactly-once.
- API key auth with tenant derived from the key; SSRF basics; OWASP API Top 10 analysis.
- Explicit MUST / SHOULD / NICE TO HAVE and 25 fixed output sections.

### Relevant AI output

- Asked two blocking questions before planning:
  1. The PDF was not in the workspace. The human provided its text as `challenge.md`.
  2. `build.gradle` used Spring Boot 4.1.1 while the brief said 3.x. The human chose to keep 4.1.1.
- Fixture analysis: 10 events, 3 clients, only `completed`/`failed`, no creation timestamp, no attempt history,
  no webhook URL, no notification id distinct from `event_id`.
- Plan v1 with: Kafka topic `platform.events.v1` keyed by `client_id`, ack after DB commit, DLT for poison
  messages; `INSERT ... ON CONFLICT (event_id) DO NOTHING`; claim via `UPDATE ... WHERE id IN (SELECT ... FOR UPDATE
  SKIP LOCKED) RETURNING`; lease + fencing (`locked_by`); attempts counted at claim time; exponential backoff with
  full jitter; replay as a conditional update from `FAILED`; 5 OWASP API risks; 6 implementation phases.

### Human analysis

Reviewed the plan as the owner of the implementation. Accepted the core design (durable retries in PostgreSQL,
`SKIP LOCKED` + lease + fencing, own API key filter instead of Spring Security, no Resilience4j, no Lombok,
DNS rebinding as a documented limitation, no `client_id` metric tag). Found six issues to correct:

1. The plan kept Boot 4.1.1 but had no objective rule for when to give up on it.
2. Seeded fixture rows would have `attempt_count = 0` and no history, so a `failed` row would have no attempts:
   inconsistent and hard to defend.
3. The API exposed `delivery_date` with a redefined meaning, which is ambiguous.
4. Returning 403 when a client sends `client_id` added a behavior with no security benefit.
5. Operator (cross-tenant) actions were not audited.
6. The rule for sensitive data was "no full `content` in logs", which still allows partial leaks.

### Decision

Plan approved with adjustments (v2):

1. Boot version cut rule: 1h timebox in Phase 0; if Testcontainers (PostgreSQL + Kafka), Spring Kafka, Flyway or
   the test context fail, downgrade to Boot 3.5.x without discussion; springdoc alone failing -> static OpenAPI spec.
2. Seed rows get `attempt_count = 1` and one synthetic `delivery_attempt` (`error_code = 'fixture_synthetic'`,
   `http_status = null`), documented as synthetic data.
3. The API exposes `event_created_at`, `delivered_at` and `last_attempt_at`; no `delivery_date`.
4. `client_id` is silently ignored for client keys; only `OPERATOR` can filter by it.
5. Every operator action is written to a structured audit log (operator key name, action, notification id,
   affected client), without content or secrets.
6. `content` never goes to logs, `last_error`, `error_message` or metrics; errors are sanitized and truncated.

The AI was also asked to check whether the adjustments contradicted other parts of the plan. It found and fixed:
the old seed assumption, a replay attempt-number consequence (a replayed seed row now produces attempt #2),
subscription seeds needing to cover all 10 fixture pairs, the `delivery_date` field in the response example,
403 in the status code list, and library-level leaks of `content` (Jackson exception messages, Spring Kafka
record formatting).

### Resulting change

- [docs/plan-v2.md](plan-v2.md): approved plan, used as the reference for all phases.

---

## Entry 2 - Phase 0 setup and Spring Boot 4.1.1 compatibility timebox

**Date:** 2026-09-23
**Type:** code generation, verification

### Goal

Execute only Phase 0 of plan v2: verify Boot 4.1.1 within a 1h timebox, rename the root package, add the
planned dependencies, configuration profiles, the V1 schema, docker-compose and the Testcontainers base.

### Prompt (summary)

"Execute ONLY Phase 0. Show the exact list of changes first. Apply the Boot 4 -> 3.5.x cut rule and report the
final version and why. Do not implement anything from Phase 1."

### Relevant AI output

- Environment findings: Docker Desktop was not running (started it); the default JDK is 26 but Gradle detected
  a Temurin 21 toolchain, so no toolchain plugin was needed.
- Versions resolved from the Boot 4.1.1 BOM: Testcontainers 2.0.5, Awaitility 4.3.0, Micrometer 1.17.1.
  springdoc 3.1.1 (Boot 4.1 line) added with an explicit version.
- Three smoke checks, all green on Boot 4.1.1: context + Flyway V1 on PostgreSQL (Testcontainers),
  `@KafkaListener` round trip on a real broker (Testcontainers), `/v3/api-docs` served by springdoc.
- Test contexts reduced from 3 to 2 by moving `@AutoConfigureMockMvc` to the shared base class.

### Human analysis

Reviewed

### Decision

Keep Spring Boot 4.1.1 (no cut rule triggered).

### Resulting change

Phase 0 files: `build.gradle`, `application*.yml`, `V1__init.sql`, `docker-compose.yml`, WireMock mappings,
Testcontainers base and smoke tests.

---

## Entry 3 - Schema invariants for the delivery lifecycle (human code review of V1)

**Date:** 2026-09-23
**Type:** code review, code generation, test generation

### Goal

Harden `V1__init.sql` so that the database itself rejects `notification_events` rows in an impossible
lifecycle state, instead of relying only on application code.

### Prompt (summary)

Human review of the AI-generated `V1__init.sql`:
"Add to the DB init: a CHECK so `next_attempt_at` is not null when the status is PENDING or RETRYING; a lease
check for PROCESSING (requires `locked_by` and `locked_until`); a COMPLETED check (requires `delivered_at`)."

### Relevant AI output

- Three named constraints on `notification_events`:
  - `ck_notification_events_due_scheduled`: PENDING/RETRYING require `next_attempt_at`.
  - `ck_notification_events_processing_lease`: PROCESSING requires both `locked_by` and `locked_until`.
  - `ck_notification_events_completed_delivered`: COMPLETED requires `delivered_at`.
- `NotificationEventsSchemaConstraintsTest` (Testcontainers PostgreSQL): each constraint rejects the invalid row
  (asserted by constraint name) and accepts the valid one; FAILED has no extra requirements.
- Impact analysis against plan v2:
  - lease recovery (PROCESSING -> RETRYING) must set `next_attempt_at`;
  - replay already sets `next_attempt_at = now()`;
  - fixture seed rows already comply (`completed` rows carry `delivered_at`).
- Editing V1 in place was judged safe: it had not been applied to any persistent database yet.
- Proposed but not applied: the reverse invariants (lease fields null outside PROCESSING, `delivered_at` null
  outside COMPLETED), because they force clearing fields on every transition and were not requested.

### Human analysis

The gap was spotted while reviewing the generated schema: the claim query and lease recovery depend on these
fields being present, and a row in PENDING with no `next_attempt_at` would never be picked up by the worker
(a silent stuck notification). Enforcing it in the schema makes such bugs fail fast in tests.

### Decision

Accept the three constraints and the test. Keep the reverse invariants out of scope for now.

### Resulting change

- `src/main/resources/db/migration/V1__init.sql`: three new CHECK constraints.
- `src/test/java/com/cobre/notification/NotificationEventsSchemaConstraintsTest.java`: 4 tests, all green
  (suite: 7/7).
- Phase 1 note: lease recovery must set `next_attempt_at` when moving a row back to RETRYING.

---

## Entry 4 - Phase 1 walking skeleton (ingestion, worker, API, compose)

**Date:** 2026-09-23
**Type:** code generation, test generation, verification

### Goal

End-to-end happy path: platform event on Kafka -> subscription check -> PostgreSQL -> scheduled worker ->
webhook -> COMPLETED/FAILED, readable through `GET /notification_events` and `GET /notification_events/{id}`,
runnable with `docker compose up`.

### Prompt (summary)

"Execute Phase 1 as in docs/plan-v2.md. The consumer never does HTTP: validate, persist PENDING with
`next_attempt_at = now()`, ack. All delivery, including the first attempt, is done by the worker (single path:
claim, lease, fencing; no after-commit nudge). Respect the V1 CHECKs; every UPDATE sets `updated_at`; ingestion
uses `ON CONFLICT (event_id) DO NOTHING` with no prior SELECT. 2xx -> COMPLETED, anything else -> FAILED, behind
an interface. No retries, auth, SSRF guard, HMAC, metrics or fixture seeder. One commit per sub-step."

### Relevant AI output

Time per sub-step (agent wall clock, from commit timestamps; human review not included):

| Sub-step | Commit | Time |
|---|---|---|
| Ingestion | `ab136fe` | 22:17 - 22:23 (~6 min) |
| Worker | `60a8eee` | 22:23 - 22:28 (~6 min) |
| API | `b15235e` | 22:28 - 22:32 (~4 min) |
| Compose + demo | `2d51099` | 22:32 - 22:37 (~5 min) |

- Ingestion: `@KafkaListener` with manual Jackson mapping and field validation; invalid messages throw a
  non-retryable exception and are skipped by `DefaultErrorHandler` (transient errors retry with exponential
  backoff); `saveIfAbsent` via `INSERT ... ON CONFLICT (event_id) DO NOTHING`; ack after persistence.
- Worker: `@Scheduled` fixed delay; claim with `UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED)
  RETURNING` plus the IN_PROGRESS attempt in one short transaction; HTTP outside any transaction (JDK
  `HttpClient`, no redirects, 2s/5s timeouts, `Idempotency-Key` = notification id); result update fenced by
  `locked_by` + `PROCESSING`. Classification behind `DeliveryResultClassifier`.
- API: immutable JPA entities (validated by `ddl-auto: validate`), deterministic order
  `event_created_at DESC, id DESC`, `size <= 100`, snake_case DTOs, lowercase statuses, no `delivery_date`,
  `webhook_url` masked to `scheme://host[:port]`, RFC 9457 problem details.
- Compose: multi-stage Dockerfile, `app` service, demo subscription seeder for the 10 fixture pairs (one pointing
  at the failing WireMock endpoint), `demo/platform-events.jsonl` with a duplicate and an unsubscribed type,
  publish scripts for PowerShell and sh.
- Tests: 69 green across 15 suites (unit with fakes, Testcontainers integration, E2E Kafka -> webhook -> API).
- Deviations from plan v2 flagged by the AI:
  - the persistence port was split into `NotificationEventRepository` (ingestion) and `DeliveryRepository`
    (worker) after a compile error showed ingestion fakes had to implement delivery methods;
  - minimal `DeliveryError` sanitizing (control chars, 500-char truncation) pulled forward from Phase 2;
  - timestamps come from the application `Clock` rather than DB `now()` (assumes NTP-synced nodes);
  - worker delivers sequentially within a tick (bounded executor is Phase 2);
  - `client_id` on the list endpoint is an unauthenticated filter until Phase 3;
  - invalid events are skipped, not sent to the DLT (Phase 2);
  - SLF4J key-values are not rendered by the default console pattern (structured logging is Phase 5).

### Human analysis

Pending review

### Decision

Pending review

### Resulting change

Four commits (`ab136fe`, `60a8eee`, `b15235e`, `2d51099`) on top of `chore: phase 0 setup`. Manual demo on
docker compose: 12 published events -> 10 notifications (duplicate deduplicated, unsubscribed type ignored),
9 COMPLETED and 1 FAILED with `last_error = "http_status: HTTP 500"`; webhook received the expected headers and
snake_case body; `content` absent from application logs.

---

## Entry 5 - Pre-Phase 3 review: clock source, invalid messages, worker concurrency

**Date:** 2026-09-24
**Type:** code review (no code changes)

### Goal

Speed up the human review before Phase 3 by answering three questions with concrete file and test references,
and by listing anything that should be fixed, without changing code.

### Prompt (summary)

"Before Phase 3 I will review Phases 1 and 2. Do not advance. (1) Clock: show where the app `Clock` and DB `now()`
are used; confirm the claim and `locked_until` use the same source in every path; flag any mix; if the decision
stands, write the NTP assumption and the risk in ADR-003. (2) Invalid messages: confirm no path discards a message
without the DLT or a count; point to the test. (3) Worker concurrency: point to the test running 2+ workers on the
same rows; if it only tests the query, say what is missing. Reference files and tests; list fixes and wait."

### Relevant AI output

**Premise correction.** Phase 2 is not implemented. The last commit is `df2734b` (Phase 1 ai-log). There is no
DLT, no lease recovery, no retries, no concurrent-claim test and no ADR folder. The answers below describe the
Phase 1 code as it is.

**1. Clock.**
- The app `Clock` is the only source in application paths:
  - `IngestPlatformEventService` line 37, through `NotificationEvent.pendingFrom`: `next_attempt_at`,
    `created_at`, `updated_at`.
  - `DeliverNotificationService` lines 54-55: a single `now` gives both the claim cutoff and
    `lockedUntil = now + leaseDuration`.
  - `DeliverNotificationService` line 65: a second reading for `completed_at` / `delivered_at` / `updated_at`.
  - `SubscriptionPersistenceAdapter` line 36.
- In `NotificationEventPersistenceAdapter.claimDue`, `next_attempt_at <= :now`, `locked_until = :lockedUntil`,
  `last_attempt_at` / `updated_at = :now` and the attempt `started_at` all use the parameters derived from that
  single `now`. `recordResult` uses `completion.completedAt()`.
- DB `now()` appears only as `DEFAULT now()` in `V1__init.sql` (lines 8-9 and 39-40). It is never reached by
  application inserts, because they pass explicit values. It is reached by raw SQL inserts in tests
  (`NotificationEventControllerTest`).
- No path mixes DB and app time. The only cross-source comparison is across nodes: `next_attempt_at` is
  written by the ingesting node and compared by the worker node.
- `locked_until` is written but no code reads it yet. The fence in `recordResult` checks only `locked_by` +
  `PROCESSING`.
- The assumption and the risks are written in `docs/adr/ADR-003-durable-postgresql-delivery.md`
  (status Proposed), with the DB-clock alternative.

**2. Invalid messages: the statement does not hold.**
- `KafkaConfiguration.kafkaErrorHandler` builds a `DefaultErrorHandler` without a recoverer. Invalid messages
  (`InvalidPlatformEventException`) are logged by Spring Kafka's default recoverer (`topic-partition@offset`
  only) and skipped. There is no DLT and no counter.
- `IngestionIntegrationTest.invalidMessageDoesNotBlockThePartition` only proves the next message is processed.
  It does not assert any DLT record or count.
- `NO_SUBSCRIPTION` events are acked by design. Their only trace is the `outcome` key-value in
  `PlatformEventKafkaListener`, which the default console pattern does not render.
- Two inputs escape the non-retryable classification and would stall the partition forever (infinite backoff)
  instead of being dead-lettered. Both come from code analysis and were not executed:
  - the JSON literal `null`: `readValue` returns `null`, then `message.schemaVersion()` throws a
    `NullPointerException` at `PlatformEventMessageMapper` line 34;
  - `content` containing `\u0000`: PostgreSQL / pgjdbc rejects NUL in text, so the insert fails with a
    retryable data access exception.

**3. Worker concurrency: there is no such test.**
- The closest test is `NotificationEventPersistenceAdapterTest.claimSkipsRowsNotYetDueAndRowsAlreadyClaimed`.
  It runs two claims sequentially on one thread, so the second one sees `PROCESSING` rows after the first
  transaction has committed. It would pass even without `SKIP LOCKED`.
- `resultFromWorkerWithoutLeaseIsRejected` covers the fence, not concurrency.
- Missing:
  - (a) a query-level test with two or more threads whose claim transactions truly overlap (barrier or latch),
    rows exceeding the batch size, and assertions that the union of claimed ids equals all rows, the
    intersection is empty and each row has exactly one attempt;
  - (b) a lock-held test: open a transaction that locks some due rows, then check that a concurrent claim skips
    them without blocking (this proves `SKIP LOCKED` itself);
  - (c) a service-level test with two `DeliverNotificationService` instances (distinct worker ids) against
    `RecordingWebhookServer`, asserting each notification was received exactly once and `attempt_count = 1`.
  - The Hikari pool must allow the number of threads used.

**Findings proposed for correction (not applied; awaiting confirmation):**
- F1. Wire the DLT: `DeadLetterPublishingRecoverer` for non-retryable errors, plus a counter. A test asserts
  the record in `platform.events.v1.DLT` (planned for Phase 2).
- F2. Treat a `null` JSON literal as `empty_message` / `malformed_json`, and reject or strip `\u0000` in
  `content` as an invalid event. Add mapper tests for both.
- F3. Decide the policy for unexpected (non-transient) exceptions in the listener: today every exception is
  retried forever.
- F4. Add the concurrency tests (a), (b) and (c).
- F5. Lease recovery does not exist yet. A crashed worker, or a failed `recordResult`, leaves the row in
  `PROCESSING` forever. The error log in `DeliverNotificationService` ("the lease will expire and the attempt
  will be recovered") is not true in Phase 1.
- F6. For Phase 2, the fence should also match the attempt (for example `attempt_count = :attemptNumber` or an
  attempt/lease token), not only `locked_by`. Otherwise a row that is recovered and reclaimed by the same worker
  id could accept a stale result.
- F7. Make key-values visible in logs (structured or logfmt pattern). Otherwise outcomes such as
  `no_subscription` are invisible.
- F8. Optional: compute `duration_ms` with a monotonic source (`System.nanoTime`) instead of two wall-clock
  readings.

### Human analysis

Pending review

### Decision

Pending review

### Resulting change

- No code changed.
- New `docs/adr/ADR-003-durable-postgresql-delivery.md` with the time-source decision, NTP assumption, risks
  and the DB-clock alternative (other sections pending Phase 2).
