# Architecture

One Spring Boot process, three inbound adapters (Kafka, scheduled worker, REST) and three outbound
adapters (PostgreSQL, HTTPS webhook, Micrometer). Domain and application have no Spring, JPA, Kafka
or `java.net.http`.

Horizontal scale: more identical instances. Kafka partitions by `client_id`. Workers compete with
`FOR UPDATE SKIP LOCKED` and a lease. HTTP never runs inside a database transaction.

## C4 containers

```mermaid
flowchart LR
  Platform["Cobre Platform services"] -->|platform.events.v1| Kafka[(Kafka)]
  Kafka --> NS["Notification Service"]
  NS -->|state, attempts| PG[(PostgreSQL)]
  NS -->|"HTTPS POST"| ClientWebhook["Client webhook"]
  ClientApp["Client app"] -->|"REST + X-API-Key"| NS
  Ops["Monitoring"] -->|"operator key, :8081"| NS
  NS -->|poison| DLT[(platform.events.v1.DLT)]
```

## Ingest

```mermaid
sequenceDiagram
  participant K as Kafka
  participant L as KafkaListener
  participant U as IngestUseCase
  participant DB as PostgreSQL
  K->>L: record key=client_id
  L->>U: PlatformEvent
  U->>DB: find active subscription
  alt no subscription
    U-->>L: skipped
  else subscription found
    U->>DB: INSERT ON CONFLICT event_id DO NOTHING
    U-->>L: accepted or duplicate
  end
  L-->>K: commit offset after DB commit
```

The listener does not call HTTP. Invalid JSON goes to the DLT (`notification.dlt.published`).
Transient DB errors retry without committing the offset.

## Delivery and retry

```mermaid
sequenceDiagram
  participant W as DeliveryWorker
  participant DB as PostgreSQL
  participant H as WebhookClient
  participant C as ClientEndpoint
  W->>DB: recover expired leases
  W->>DB: claim SKIP LOCKED, PROCESSING, lease, IN_PROGRESS attempt
  W->>H: deliver (no open tx)
  H->>C: POST Idempotency-Key
  C-->>H: status or timeout
  H-->>W: DeliveryResult
  alt 2xx
    W->>DB: COMPLETED fenced
  else retryable and budget left
    W->>DB: RETRYING next_attempt_at
  else permanent or exhausted
    W->>DB: FAILED
  end
```

The guard runs before HTTP. Redirects are never followed. HMAC is added only when
`signing_secret` is set on the active subscription at claim time.

## NotificationEvent states

```mermaid
stateDiagram-v2
  [*] --> PENDING: ingest or replay
  PENDING --> PROCESSING: claim
  RETRYING --> PROCESSING: claim when due
  PROCESSING --> COMPLETED: 2xx
  PROCESSING --> RETRYING: retryable or lease expired
  PROCESSING --> FAILED: permanent or max attempts
  FAILED --> PENDING: replay
  COMPLETED --> [*]
```

`cycle_attempt_count` is the retry budget; replay resets it. `attempt_count` never decreases.

## Persistence

```mermaid
erDiagram
  SUBSCRIPTIONS ||--o{ NOTIFICATION_EVENTS : originates
  NOTIFICATION_EVENTS ||--o{ DELIVERY_ATTEMPTS : has
  SUBSCRIPTIONS {
    uuid id PK
    string client_id
    string event_type
    string webhook_url
    string signing_secret
    boolean active
  }
  NOTIFICATION_EVENTS {
    uuid id PK
    string event_id UK
    string client_id
    string event_type
    string delivery_status
    int attempt_count
    timestamp event_created_at
    timestamp next_attempt_at
    timestamp locked_until
    string origin
  }
  DELIVERY_ATTEMPTS {
    uuid id PK
    uuid notification_event_id FK
    int attempt_number
    string attempt_trigger
    string status
    int http_status
  }
```

Flyway `V1` + `V2` (`signing_secret`). `ddl-auto=validate`. Seeded fixture rows use `origin=FIXTURE`
and one synthetic attempt (`error_code=fixture_synthetic`).

## Scale and failure

- More app instances: more consumer threads and more claim workers.
- Kafka down: no new ingest; API and retries continue.
- PostgreSQL down: ingest retries (lag grows); API 503; worker waits.
- Webhook down: persisted backoff, then FAILED; replay when it is back.
- Worker crash: lease expires; another worker abandons the in-flight attempt and retries or fails.
- Poison message: DLT, partition keeps moving.

ADRs: [ADR-001](adr/ADR-001-hexagonal-and-boot.md) … [ADR-007](adr/ADR-007-api-keys-and-operator-audit.md).
