CREATE TABLE subscriptions
(
    id          UUID PRIMARY KEY,
    client_id   VARCHAR(64)   NOT NULL,
    event_type  VARCHAR(100)  NOT NULL,
    webhook_url VARCHAR(2048) NOT NULL,
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- At most one active subscription per (client, event type).
CREATE UNIQUE INDEX ux_subscriptions_active_client_event_type
    ON subscriptions (client_id, event_type)
    WHERE active;

CREATE TABLE notification_events
(
    id                  UUID PRIMARY KEY,
    event_id            VARCHAR(100)  NOT NULL,
    subscription_id     UUID REFERENCES subscriptions (id),
    client_id           VARCHAR(64)   NOT NULL,
    event_type          VARCHAR(100)  NOT NULL,
    content             TEXT          NOT NULL,
    event_created_at    TIMESTAMPTZ   NOT NULL,
    webhook_url         VARCHAR(2048) NOT NULL,
    delivery_status     VARCHAR(20)   NOT NULL,
    attempt_count       INTEGER       NOT NULL DEFAULT 0,
    cycle_attempt_count INTEGER       NOT NULL DEFAULT 0,
    replay_count        INTEGER       NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMPTZ,
    last_attempt_at     TIMESTAMPTZ,
    delivered_at        TIMESTAMPTZ,
    last_http_status    INTEGER,
    last_error          VARCHAR(500),
    locked_by           VARCHAR(100),
    locked_until        TIMESTAMPTZ,
    origin              VARCHAR(20)   NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_notification_events_event_id UNIQUE (event_id),
    CONSTRAINT ck_notification_events_status
        CHECK (delivery_status IN ('PENDING', 'PROCESSING', 'RETRYING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_notification_events_origin CHECK (origin IN ('KAFKA', 'FIXTURE')),
    CONSTRAINT ck_notification_events_counters
        CHECK (attempt_count >= 0 AND cycle_attempt_count >= 0 AND replay_count >= 0
            AND cycle_attempt_count <= attempt_count),
    -- Claimable rows must be schedulable, otherwise the worker never picks them up.
    CONSTRAINT ck_notification_events_due_scheduled
        CHECK (delivery_status NOT IN ('PENDING', 'RETRYING') OR next_attempt_at IS NOT NULL),
    -- In-flight rows must carry a lease so they can be fenced and recovered after a crash.
    CONSTRAINT ck_notification_events_processing_lease
        CHECK (delivery_status <> 'PROCESSING' OR (locked_by IS NOT NULL AND locked_until IS NOT NULL)),
    CONSTRAINT ck_notification_events_completed_delivered
        CHECK (delivery_status <> 'COMPLETED' OR delivered_at IS NOT NULL)
);

CREATE INDEX ix_notification_events_client_created
    ON notification_events (client_id, event_created_at DESC, id DESC);

CREATE INDEX ix_notification_events_client_status_created
    ON notification_events (client_id, delivery_status, event_created_at DESC, id DESC);

-- Worker claim: only rows that can still be picked up.
CREATE INDEX ix_notification_events_due
    ON notification_events (next_attempt_at)
    WHERE delivery_status IN ('PENDING', 'RETRYING');

-- Lease recovery: only in-flight rows.
CREATE INDEX ix_notification_events_lease
    ON notification_events (locked_until)
    WHERE delivery_status = 'PROCESSING';

CREATE TABLE delivery_attempts
(
    id                    UUID PRIMARY KEY,
    notification_event_id UUID          NOT NULL REFERENCES notification_events (id),
    attempt_number        INTEGER       NOT NULL,
    attempt_trigger       VARCHAR(10)   NOT NULL,
    webhook_url           VARCHAR(2048) NOT NULL,
    status                VARCHAR(20)   NOT NULL,
    http_status           INTEGER,
    error_code            VARCHAR(50),
    error_message         VARCHAR(500),
    started_at            TIMESTAMPTZ   NOT NULL,
    completed_at          TIMESTAMPTZ,
    duration_ms           BIGINT,
    CONSTRAINT uq_delivery_attempts_notification_attempt UNIQUE (notification_event_id, attempt_number),
    CONSTRAINT ck_delivery_attempts_number CHECK (attempt_number > 0),
    CONSTRAINT ck_delivery_attempts_trigger CHECK (attempt_trigger IN ('INITIAL', 'RETRY', 'REPLAY')),
    CONSTRAINT ck_delivery_attempts_status
        CHECK (status IN ('IN_PROGRESS', 'SUCCESS', 'RETRYABLE_FAILURE', 'PERMANENT_FAILURE', 'ABANDONED'))
);
