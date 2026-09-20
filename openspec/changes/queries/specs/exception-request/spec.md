# Spec Delta

## ADDED Requirements

### Requirement: Read a request with its history

The system SHALL return a request's current values together with every history
entry recorded against it, ordered newest first. Each entry SHALL carry the
transition it records, the version it concerned, the acting user, the time it
occurred, and the values that transition carried. A read SHALL carry identity
like every other call, and SHALL NOT change the request.

#### Scenario: A request is read by identifier

- **WHEN** a caller reads a request by its identifier
- **THEN** the response is `200` carrying the request's current values, including its `status`, `version` and requester
- **AND** the response carries the request's history

#### Scenario: History is returned newest first

- **WHEN** a request that has been created, amended and decided is read
- **THEN** its history is ordered newest first, the decision before the amendment and the amendment before the creation
- **AND** each entry names the version it concerned

#### Scenario: The request does not exist

- **WHEN** a caller reads an identifier no request has
- **THEN** the response is `404` with a problem detail body

### Requirement: Offer a role-aware queue

The system SHALL return, to a caller holding `REVIEWER`, the requests raised by
others, and SHALL NOT include any request the caller raised themselves. The
four-eyes exclusion SHALL be applied by the query rather than left to the
caller. To any other caller the system SHALL return the requests that caller
raised, in every state. Absent a `status`, a reviewer's queue SHALL hold only
`PENDING` requests.

#### Scenario: A reviewer sees pending requests raised by others

- **WHEN** a caller holding `REVIEWER` reads the queue with no status given
- **THEN** the response is `200` carrying the `PENDING` requests raised by other users
- **AND** no request the caller raised themselves is included, whatever its status

#### Scenario: A requester sees their own requests in every state

- **WHEN** a caller holding `RELATIONSHIP_MANAGER` reads the queue with no status given
- **THEN** the response carries every request that caller raised, whether `PENDING`, `APPROVED`, `DECLINED` or `WITHDRAWN`
- **AND** no request raised by anyone else is included

#### Scenario: A status narrows the requester's own requests

- **WHEN** a requester reads the queue giving a status
- **THEN** the response carries only their own requests in that status

#### Scenario: A status widens the reviewer's queue to that status

- **WHEN** a caller holding `REVIEWER` reads the queue giving a status other than `PENDING`
- **THEN** the response carries the requests in that status raised by other users
- **AND** no request the caller raised themselves is included

#### Scenario: A status outside the permitted set is refused

- **WHEN** a caller reads the queue giving a status that is not one of `PENDING`, `APPROVED`, `DECLINED` or `WITHDRAWN`
- **THEN** the response is `400` with a problem detail body

### Requirement: Retrieve the approved exception for an application

The system SHALL return the approved exception that applies to a mortgage
application, so that the application process can consume the discount that was
authorised. Where an application has more than one `APPROVED` request, the
system SHALL return the one whose `decided_at` is the most recent. Where an
application has none, the system SHALL answer that no exception applies rather
than reporting a discount of zero.

#### Scenario: An application with an approved exception

- **WHEN** the approved exception for an application holding one is requested
- **THEN** the response is `200` carrying the approved discount in basis points, the request it came from, and who decided it and when

#### Scenario: An application with more than one approved exception

- **WHEN** the approved exception is requested for an application that has accumulated more than one `APPROVED` request
- **THEN** the response carries the one whose decision is the most recent

#### Scenario: An application with no approved exception

- **WHEN** the approved exception is requested for an application that has none, whether because its requests are `PENDING`, `DECLINED` or `WITHDRAWN`, or because the application is unknown to the service
- **THEN** the response is `404` with a problem detail body

## MODIFIED Requirements

### Requirement: Maintain an append-only history

The system SHALL append one history entry per state change and SHALL never
alter or remove an entry once written. Each entry records the type of
transition, the acting user, the version the entry concerned, and the values
that transition carried. Entries SHALL be returned newest first, ordered by the
version each entry concerned, then by when it occurred, and then by the
transition itself, a decision being the later of any pair sharing a version, so
that the order follows the versions and transitions the request actually moved
through rather than the precision of the clock. The order SHALL be the same on
every read.

#### Scenario: History accumulates across the life of a request

- **WHEN** a request is created, amended once, and then approved
- **THEN** its history holds three entries, of types `CREATED`, `AMENDED` and `APPROVED`
- **AND** each entry names the version it concerned and the user who acted

#### Scenario: A rejected call leaves no trace

- **WHEN** a call is rejected with `400`, `403` or `409`
- **THEN** no history entry is appended

#### Scenario: Entries recorded at the same instant are still ordered

- **WHEN** a request's history holds two entries concerning different versions whose recorded times are identical
- **THEN** the entry concerning the higher version is returned first
- **AND** repeating the read returns the history in the same order every time

#### Scenario: A decision and the amendment it reviewed are still ordered

- **WHEN** a request's history holds an amendment and the decision recorded against that same version, whose recorded times are identical
- **THEN** the decision is returned first
