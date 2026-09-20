# exception-request Specification

## Purpose

A pricing exception request records that a relationship manager has asked for a
discount on the standard interest rate for a mortgage application, and carries
that request through review to an approval or a decline that is bound to the
exact version reviewed, with an audit trail answering what was approved, by
whom, and when.

## Requirements

### Requirement: Create an exception request

The system SHALL accept a new exception request and record it as `PENDING` at
`version` 1. Identity is taken from the `X-User-Id` and `X-User-Role` headers,
and `X-User-Id` is stored as the requester.

#### Scenario: A request is created

- **WHEN** a caller submits an application identifier, a discount in basis points and a reason
- **THEN** the response is `201` with a `Location` header naming the new request
- **AND** the request has status `PENDING`, `version` 1, and the caller as its requester

#### Scenario: Creation is recorded in history

- **WHEN** a request is created
- **THEN** a history entry of type `CREATED` is appended, carrying the requester as actor, `version` 1, and the discount and reason submitted

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

### Requirement: Validate request fields

The system SHALL reject a create or amend whose fields fall outside the stated
bounds, and SHALL NOT record any state change for a rejected call.

#### Scenario: The discount is outside the permitted range

- **WHEN** a caller submits a discount below 1 or above 200 basis points
- **THEN** the response is `400`
- **AND** no request is created

#### Scenario: The reason is too short to be useful

- **WHEN** a caller submits a reason shorter than 10 characters or longer than 500
- **THEN** the response is `400`

#### Scenario: The application identifier is missing

- **WHEN** a caller submits a blank application identifier, or one longer than 64 characters
- **THEN** the response is `400`

### Requirement: Bound the identity headers

The system SHALL reject a call whose `X-User-Id` exceeds 128 characters rather
than attempting to store it, and SHALL NOT record any state change for a
rejected call.

#### Scenario: An identity longer than the bound

- **WHEN** a caller submits any call with an `X-User-Id` longer than 128 characters
- **THEN** the response is `400` with a problem detail body
- **AND** no request is created and no state is changed

### Requirement: Amend a pending request

The system SHALL allow the requester to change the discount and the reason of
their own `PENDING` request, and SHALL increment `version` on each amendment. An
amendment MUST carry at least one of the two, since a version change that alters
nothing still invalidates a decision under review. No other field may be
changed, and no other caller may amend. The caller MUST submit the version being
amended.

#### Scenario: A pending request is amended

- **WHEN** the requester submits a new discount or reason together with the request's current version
- **THEN** the response is `200`
- **AND** `version` is incremented by one
- **AND** a history entry of type `AMENDED` is appended carrying the new version and the submitted values

#### Scenario: The submitted version is stale

- **WHEN** a caller amends using a version that is no longer current
- **THEN** the response is `409`
- **AND** the response states the current version

#### Scenario: A field other than discount or reason is submitted

- **WHEN** a caller attempts to change any field beyond the discount and the reason
- **THEN** the response is `400`

#### Scenario: An amendment carries neither the discount nor the reason

- **WHEN** a caller submits an amendment containing only the version
- **THEN** the response is `400`
- **AND** the request is left at its current version with no history entry appended

#### Scenario: Someone other than the requester amends

- **WHEN** a caller who did not raise the request submits an amendment
- **THEN** the response is `403`
- **AND** the request is left at its current version

### Requirement: Decide a request

The system SHALL allow a caller holding the `REVIEWER` role to approve or
decline a `PENDING` request against an explicitly submitted version. A decision
is terminal and records who decided and when. The reviewer may attach an
optional free-text note, which belongs to the decision rather than to the
request.

#### Scenario: A reviewer approves the current version

- **WHEN** a caller with role `REVIEWER` submits `APPROVE` with the request's current version
- **THEN** the response is `200`
- **AND** the request status becomes `APPROVED`
- **AND** the decision records the deciding user and the time of the decision
- **AND** a history entry of type `APPROVED` is appended carrying that version and any note

#### Scenario: A reviewer declines the current version

- **WHEN** a caller with role `REVIEWER` submits `DECLINE` with the request's current version
- **THEN** the response is `200`
- **AND** the request status becomes `DECLINED`

#### Scenario: The request changed during review

- **WHEN** a reviewer submits a decision against a version that is no longer current
- **THEN** the response is `409`
- **AND** the response states the current version, so the request can be reviewed again

#### Scenario: The caller does not hold the reviewer role

- **WHEN** a caller with role `RELATIONSHIP_MANAGER` submits a decision
- **THEN** the response is `403`

### Requirement: Enforce four-eyes on decisions

The system SHALL reject any decision made by the caller who raised the request,
regardless of the role that caller holds.

#### Scenario: A requester decides their own request

- **WHEN** the caller who raised a request submits a decision on it
- **THEN** the response is `403`
- **AND** the response explains that a request cannot be decided by the person who raised it

### Requirement: Withdraw a pending request

The system SHALL allow the requester to withdraw their own `PENDING` request
against an explicitly submitted version.

#### Scenario: A requester withdraws their request

- **WHEN** the requester submits a withdrawal with the request's current version
- **THEN** the response is `200`
- **AND** the request status becomes `WITHDRAWN`
- **AND** a history entry of type `WITHDRAWN` is appended

#### Scenario: Someone other than the requester withdraws

- **WHEN** a caller who did not raise the request submits a withdrawal
- **THEN** the response is `403`

### Requirement: Terminal requests are immutable

The system SHALL reject every attempt to change a request whose status is
`APPROVED`, `DECLINED` or `WITHDRAWN`, and SHALL leave its stored state and its
history untouched.

#### Scenario: An amendment is attempted on a decided request

- **WHEN** a caller amends a request that is `APPROVED`, `DECLINED` or `WITHDRAWN`
- **THEN** the response is `409`

#### Scenario: A second decision is attempted

- **WHEN** a reviewer submits a decision on a request that has already been decided
- **THEN** the response is `409`

### Requirement: Maintain an append-only history

The system SHALL append one history entry per state change and SHALL never
alter or remove an entry once written. Each entry records the type of
transition, the acting user, the version the entry concerned, and the values
that transition carried.

#### Scenario: History accumulates across the life of a request

- **WHEN** a request is created, amended once, and then approved
- **THEN** its history holds three entries, of types `CREATED`, `AMENDED` and `APPROVED`
- **AND** each entry names the version it concerned and the user who acted

#### Scenario: A rejected call leaves no trace

- **WHEN** a call is rejected with `400`, `403` or `409`
- **THEN** no history entry is appended

### Requirement: Resolve concurrent writes to a single winner

The system SHALL ensure that when two callers act on the same version of a
request at the same time, exactly one succeeds and the other is rejected as
stale.

#### Scenario: Two amendments race on the same version

- **WHEN** two callers submit amendments against the same current version concurrently
- **THEN** exactly one receives `200` and the request reaches `version` + 1
- **AND** the other receives `409`

#### Scenario: An amendment and a decision race

- **WHEN** an amendment and a decision are submitted against the same version concurrently
- **THEN** exactly one succeeds and the other receives `409`

### Requirement: Resolve concurrent retries to a single request

The system SHALL ensure that when the same caller submits the same
`Idempotency-Key` twice at the same time, exactly one request is created.

#### Scenario: Two retries of one create race

- **WHEN** two creates carrying the same `Idempotency-Key` from the same caller are submitted concurrently
- **THEN** exactly one request is created
- **AND** exactly one `CREATED` history entry exists for it

### Requirement: Report errors as problem details

The system SHALL describe every non-2xx outcome in an RFC 7807 problem detail
body carrying a title and a detail a person could act on. This includes
rejections raised before any handler runs, which carry no less obligation to
explain themselves than the ones the service raises itself.

#### Scenario: A stale version is reported

- **WHEN** a call is rejected because the submitted version is no longer current
- **THEN** the body is a problem detail whose detail states the current version

#### Scenario: An unknown request is reported

- **WHEN** a call names a request identifier that does not exist
- **THEN** the response is `404` with a problem detail body

#### Scenario: A call the service does not support is reported

- **WHEN** a caller submits an unsupported content type, uses a method a path does not offer, or names a path that does not exist
- **THEN** the response carries a problem detail body with a title and a detail, in the same shape as every other rejection
