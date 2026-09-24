# ADR-004 - At-least-once delivery and idempotency

**Status:** Accepted
**Date:** 2026-09-24

## Context

A crash can happen after the webhook returns 2xx and before `recordResult`, or after a lease
expires while HTTP is still in flight. Exactly-once to an arbitrary URL is not available.

## Decision

- Ingest: `UNIQUE(event_id)` + `ON CONFLICT DO NOTHING`.
- Delivery: at-least-once. `Idempotency-Key` is the notification UUID and is **stable** across
  retries and replays. `X-Cobre-Event-Id` is the platform id.
- Replay: conditional `UPDATE … FAILED`; one winner, others 409.
- Receivers must dedupe on `Idempotency-Key`.

## Consequences

Duplicate POSTs are possible (stale lease + fencing, or 2xx then crash). Fencing stops a stale
worker from overwriting a newer cycle. We do not claim exactly-once.

## Alternatives

- Outbox + Kafka delivery topic: another hop, same at-least-once problem at the edge.
- Changing the idempotency key per attempt: breaks receiver dedupe on retries.
