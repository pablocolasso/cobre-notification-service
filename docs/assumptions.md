# Assumptions

Only the note required for Phase 3 is recorded here. The rest of the documentation is Phase 6.

## A13 - Synthetic fixture attempts

`notification_events.json` has no attempt history. Each seeded row gets one `delivery_attempt`
with `error_code = fixture_synthetic` (also on `SUCCESS`) so that no `completed` / `failed` row is
left without history. That attempt is a provenance mark, not a real HTTP delivery. `origin =
FIXTURE` on the notification says the same thing at row level.
