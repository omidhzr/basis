-- Current state: one row per request, updated in place. The version column is
-- what a decision binds to, and is the guard in every UPDATE against this table.
CREATE TABLE IF NOT EXISTS exception_request (
    id             UUID         NOT NULL PRIMARY KEY,
    application_id VARCHAR(64)  NOT NULL,
    discount_bps   INT          NOT NULL,
    reason         VARCHAR(500) NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    version        INT          NOT NULL,
    requested_by   VARCHAR(128) NOT NULL,
    decided_by     VARCHAR(128),
    decided_at     TIMESTAMP WITH TIME ZONE,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Invariant 5: a discount is a positive integer in basis points. Validation
    -- rejects out-of-range values first; this makes the storage itself unable
    -- to hold one.
    CONSTRAINT discount_bps_in_range CHECK (discount_bps BETWEEN 1 AND 200)
);

-- How it got there: one row per transition, INSERT only. Never UPDATEd or
-- DELETEd -- see invariant 3. The version column records the version the entry
-- concerned, which is not always the request's current version.
CREATE TABLE IF NOT EXISTS request_history (
    id          UUID         NOT NULL PRIMARY KEY,
    request_id  UUID         NOT NULL REFERENCES exception_request (id),
    entry_type  VARCHAR(16)  NOT NULL,
    version     INT          NOT NULL,
    actor       VARCHAR(128) NOT NULL,
    payload     VARCHAR(1024),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_request_history_request_id ON request_history (request_id);

-- Idempotent create. The (key, caller) primary key IS the guard: the create
-- path inserts against it and catches the violation -- see invariant 7. H2 2.x
-- reserves KEY as a keyword, so the column CLAUDE.md names is quoted rather
-- than renamed; every statement touching it quotes it too. Scoped by caller so
-- that two relationship managers generating the same key do not collide.
CREATE TABLE IF NOT EXISTS idempotency_record (
    "key"         VARCHAR(128)  NOT NULL,
    caller        VARCHAR(128)  NOT NULL,
    request_id    UUID          NOT NULL REFERENCES exception_request (id),
    response_body VARCHAR(4096) NOT NULL,
    status_code   INT           NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY ("key", caller)
);
