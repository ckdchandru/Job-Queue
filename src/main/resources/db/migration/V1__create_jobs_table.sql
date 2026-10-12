CREATE TABLE jobs (
    id              UUID         PRIMARY KEY,
    job_type        VARCHAR(100) NOT NULL,
    payload         JSONB        NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    max_attempts    INT          NOT NULL,
    available_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    locked_by       VARCHAR(100),
    locked_at       TIMESTAMPTZ,
    claim_token     UUID,
    idempotency_key VARCHAR(255) UNIQUE,
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT chk_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'DEAD')),
    CONSTRAINT chk_max_attempts CHECK (max_attempts >= 1),
    CONSTRAINT chk_attempts CHECK (attempts >= 0 AND attempts <= max_attempts),
    CONSTRAINT chk_completed CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL)),
    CONSTRAINT chk_claim_token CHECK ((status = 'PROCESSING') = (claim_token IS NOT NULL))
);

-- for claiming
CREATE INDEX idx_jobs_status_available ON jobs (status, available_at);

-- for reclaimer
CREATE INDEX idx_jobs_status_locked ON jobs (status, locked_at);

-- dead job list
CREATE INDEX idx_jobs_dead ON jobs (created_at DESC) WHERE status = 'DEAD';
