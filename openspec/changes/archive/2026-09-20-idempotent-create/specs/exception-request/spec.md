# Spec Delta

## ADDED Requirements

### Requirement: Require an idempotency key on create

The system SHALL require an `Idempotency-Key` header on every create. The key
MUST be non-blank and at most 128 characters. A create carrying no usable key
SHALL be rejected and SHALL record nothing.

#### Scenario: The key is missing

- **WHEN** a caller submits a create with no `Idempotency-Key` header
- **THEN** the response is `400`
- **AND** no request is created

#### Scenario: The key is blank or too long

- **WHEN** a caller submits a create whose `Idempotency-Key` is blank, or longer than 128 characters
- **THEN** the response is `400`
- **AND** no request is created

### Requirement: Replay a create idempotently

The system SHALL return the response of the original create when the same
caller repeats a create with the same `Idempotency-Key` and the same body, and
SHALL create nothing further. The replayed response carries the original status
and body unchanged.

#### Scenario: A create is retried with the same key

- **WHEN** a caller repeats a create with the `Idempotency-Key` and the body of a create that already succeeded
- **THEN** the response is the original `201`, carrying the original body and a `Location` header naming the original request
- **AND** no second request is created
- **AND** no history entry is appended beyond the `CREATED` entry of the original

#### Scenario: The replayed request is the one that was created

- **WHEN** a create is retried with the same key and body
- **THEN** the identifier in the replayed response is the identifier of the request created by the first call

### Requirement: Reject a reused key carrying a different body

The system SHALL reject a create whose `Idempotency-Key` has already been used
by that caller for a create with different values, and SHALL leave the original
request untouched.

#### Scenario: A key is reused for a different request

- **WHEN** a caller submits a create with an `Idempotency-Key` already used for a create whose application identifier, discount or reason differ from this one
- **THEN** the response is `422`
- **AND** no request is created
- **AND** the request created by the original call is unchanged

### Requirement: Scope an idempotency key to its caller

The system SHALL treat an idempotency key as belonging to the caller that
submitted it, so that the same key submitted by a different caller is a
distinct create rather than a replay.

#### Scenario: Two callers submit the same key

- **WHEN** two callers each submit a create carrying the same `Idempotency-Key`
- **THEN** each receives `201` for a request of their own
- **AND** neither response is a replay of the other's

### Requirement: Resolve concurrent retries to a single request

The system SHALL ensure that when the same caller submits the same
`Idempotency-Key` twice at the same time, exactly one request is created.

#### Scenario: Two retries of one create race

- **WHEN** two creates carrying the same `Idempotency-Key` from the same caller are submitted concurrently
- **THEN** exactly one request is created
- **AND** exactly one `CREATED` history entry exists for it
