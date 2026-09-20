# Spec Delta

## ADDED Requirements

### Requirement: Bound the identity headers

The system SHALL reject a call whose `X-User-Id` exceeds 128 characters rather
than attempting to store it, and SHALL NOT record any state change for a
rejected call.

#### Scenario: An identity longer than the bound

- **WHEN** a caller submits any call with an `X-User-Id` longer than 128 characters
- **THEN** the response is `400` with a problem detail body
- **AND** no request is created and no state is changed

## MODIFIED Requirements

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
