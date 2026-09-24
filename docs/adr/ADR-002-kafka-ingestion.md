# ADR-002 - Kafka platform-event ingestion

**Status:** Accepted
**Date:** 2026-09-24

## Context

The challenge does not specify how events arrive. The platform is described as event-driven.
Ingest must ack only after the notification is durable, and a poison message must not stall a
partition.

## Decision

- Topic `platform.events.v1`, DLT `platform.events.v1.DLT`, key = `client_id`.
- `StringDeserializer` + Jackson in the adapter (explicit poison handling).
- `AckMode.RECORD`: offset after the listener returns (after `saveIfAbsent`).
- `DefaultErrorHandler.defaultFalse()`: retry only transient data-access failures. Everything else
  to the DLT. No exception message/stack headers (`content` risk). Metric
  `notification.dlt.published{reason}`.
- No HTTP on the consumer path.

## Consequences

Kafka is an assumption (A1), not a requirement. Ordering is per partition / `client_id`, not global.
A DB outage pauses the partition and grows consumer lag (alert).

## Alternatives

- HTTP ingest: extra public surface, no replay from the platform bus.
- Kafka retry topics: more moving parts than a persisted worker for webhook retries.
- Spring Kafka JSON deserializer: less control over poison payloads on Jackson 3.
