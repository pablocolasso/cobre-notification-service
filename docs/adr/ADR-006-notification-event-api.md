# ADR-006 - Notification event API: filters, pagination and 404 cross-tenant

**Status:** Proposed
**Date:** 2026-09-24

## Context

The challenge asks for a self-service API: list by client with event-creation date and
`delivery_status` filters, a detail resource, and a replay when delivery has definitely failed.
The list must stay stable under concurrent writes, and a client must not learn whether another
tenant's notification exists.

## Decision

- **Date field.** `created_from` / `created_to` filter `event_created_at` (the platform event time).
  `from` is inclusive and `to` is exclusive. `delivery_date` is not exposed: the fixture used that
  name for a completed delivery, which is not the same instant as event creation.
- **Pagination.** Offset (`page`, `size`) with `size` default 20 and max 100. Response:
  `{ items, page, size, total_elements, total_pages }`.
- **Order.** Fixed `event_created_at DESC, id DESC` so pages do not reshuffle when two events share
  a timestamp.
- **Cross-tenant.** A Client only sees `requester.clientId`. A missing id and an id that belongs to
  another tenant both return **404** `notification_event_not_found`. There is no 403: existence is
  not a privilege the caller is allowed to learn.

## Consequences

- Offset pagination is simple and matches Swagger-driven demos; deep pages get more expensive as
  the table grows (keyset pagination is the production follow-up).
- 404 hides the tenant boundary at the cost of making "wrong id" and "someone else's id" look the
  same, which is the intended BOLA mitigation.

## Alternatives

- Filter on `created_at` of the notification row: rejects A3 and the challenge's "event creation
  date".
- Keyset cursors: more stable under writes, harder to document in a take-home.
- 403 on cross-tenant: reveals that the id exists.
