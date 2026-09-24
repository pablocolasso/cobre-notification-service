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

Pending review.

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
