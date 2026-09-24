# ADR-005 - Persistence model

**Status:** Accepted
**Date:** 2026-09-24

## Context

Delivery state must survive process death. Schema changes go through Flyway;
`ddl-auto=validate`. Status values will grow (e.g. a future `CANCELLED`).

## Decision

- Three tables: `subscriptions`, `notification_events`, `delivery_attempts`.
- Status as `varchar` + `CHECK`, not a PostgreSQL enum.
- Webhook URL is snapshotted on the notification at ingest; replay may replace it with the
  current subscription URL.
- `signing_secret` lives on `subscriptions` (V2) and is read at claim, not snapshotted.
- Attempts are inserted `IN_PROGRESS` in the claim transaction so a crash still counts.
- `last_error` / `error_message` only through the sanitizer (500 chars, no `content`).
- Indexes for list-by-client, due rows and lease recovery.

## Consequences

History is complete (including `ABANDONED`). Fixture rows need a synthetic attempt (A13).
A secret rotation applies on the next claim, not mid-flight.

## Alternatives

- JPA `@Enumerated` without CHECK: weaker at the database.
- Snapshot the signing secret on the notification: safer under rotation, extra column and
  migration for a take-home.
- Store attempts only after HTTP: a crash looks like zero work and can loop.
