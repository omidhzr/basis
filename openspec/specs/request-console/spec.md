# request-console Specification

## Purpose
The request console is the page a person works from: it lets a relationship
manager raise, amend and withdraw an exception request and a reviewer decide it,
and it makes visible the version a decision binds to. It is a client of the
`exception-request` API and holds no business rule of its own.

## Requirements

### Requirement: Serve the page at the root without a build or a network

The system SHALL serve the console as a single page at `/`, from a clean start,
with no build step. Every asset the page needs, including its stylesheet, SHALL
be served by the system itself, so that the page renders on a machine with no
network access.

#### Scenario: The page is opened from a clean start

- **WHEN** the service has been started and a browser requests `/`
- **THEN** the response is `200` carrying the page
- **AND** no build step has been run beforehand

#### Scenario: The page is opened with no network access

- **WHEN** the page is opened on a machine that can reach the service but nothing else
- **THEN** the page renders fully styled
- **AND** it makes no request to any host other than the service

### Requirement: Carry the chosen identity on every call

The system SHALL let the person choose an identity, made of a user identifier and
a role of `RELATIONSHIP_MANAGER` or `REVIEWER`, and SHALL send it as `X-User-Id`
and `X-User-Role` on every call the page makes. Identity is stubbed: the page
neither verifies nor authenticates it.

#### Scenario: Every call carries the identity

- **WHEN** an identity has been chosen and the page makes any call to the service
- **THEN** the call carries `X-User-Id` and `X-User-Role` holding that identity

#### Scenario: Switching identity changes what the queue contains

- **WHEN** the person switches from a `RELATIONSHIP_MANAGER` who raised requests to a `REVIEWER`
- **THEN** the queue is read again for the new identity
- **AND** it shows what the service returns for that identity, not the previous contents with some controls hidden

### Requirement: Show the queue the service returns

The system SHALL show, as the queue, exactly what `GET /requests` returns for the
current identity, and SHALL NOT filter or add to it. Each row SHALL show the
application, the discount, the reason, the requester, the status, the time it was
submitted and the version, and SHALL open the request's detail view.

#### Scenario: A reviewer's queue

- **WHEN** a `REVIEWER` opens the queue
- **THEN** it lists the `PENDING` requests raised by other users
- **AND** it does not list any request that reviewer raised

#### Scenario: A requester's queue

- **WHEN** a `RELATIONSHIP_MANAGER` opens the queue
- **THEN** it lists every request that user raised, in every status

#### Scenario: A row opens the request

- **WHEN** the person selects a row in the queue
- **THEN** the detail view of that request is shown

### Requirement: Show a request with its history

The system SHALL show a request's current values together with its full history,
newest first, each entry naming the version it concerned, the transition it
records, the acting user, the time, and the values or note it carried. A request
SHALL be reachable directly by its identifier as well as from the queue.

#### Scenario: History is shown newest first with versions

- **WHEN** the detail view is shown for a request that was created, amended and decided
- **THEN** the decision is listed before the amendment and the amendment before the creation
- **AND** each entry states the version it concerned

#### Scenario: A request is reached directly

- **WHEN** the person opens the page for a request identifier that is not in their queue
- **THEN** the detail view of that request is shown, as `GET /requests/{id}` returns it

#### Scenario: The request does not exist

- **WHEN** the person opens the page for an identifier no request has
- **THEN** the detail view shows the problem detail the service returns with `404`

### Requirement: Offer only the actions that fit the caller and the request

The system SHALL offer a `REVIEWER` the decision panel, and a
`RELATIONSHIP_MANAGER` amend and withdraw, only while the request is `PENDING`.
The panel SHALL NOT be offered to the user who raised the request, and amend and
withdraw SHALL be offered only to the user who raised it. A request that is
`APPROVED`, `DECLINED` or `WITHDRAWN` SHALL offer no action to anyone. Which
actions are offered is a presentation of what the service would permit; the
service remains the authority and decides every attempt.

#### Scenario: A reviewer is offered the decision panel

- **WHEN** a `REVIEWER` opens a `PENDING` request raised by someone else
- **THEN** the decision panel is offered with `APPROVE` and `DECLINE`
- **AND** amend and withdraw are not offered

#### Scenario: A requester is offered amend and withdraw

- **WHEN** a `RELATIONSHIP_MANAGER` opens their own `PENDING` request
- **THEN** amend and withdraw are offered
- **AND** the decision panel is not offered

#### Scenario: A reviewer reaches their own request directly

- **WHEN** a `REVIEWER` opens, by identifier, a `PENDING` request that same user raised
- **THEN** no approve or decline control is offered

#### Scenario: A terminal request offers nothing

- **WHEN** anyone opens a request whose status is `APPROVED`, `DECLINED` or `WITHDRAWN`
- **THEN** no decision, amend or withdraw control is offered

### Requirement: Name the version a decision is recorded against

The system SHALL state, in the decision panel, the version being decided, and
SHALL submit that same version with the decision. It SHALL offer an optional note
that is submitted with the decision.

#### Scenario: The panel names the version

- **WHEN** a `REVIEWER` opens a `PENDING` request at version 2
- **THEN** the decision panel states that the decision is recorded against version 2

#### Scenario: The decision carries the version shown

- **WHEN** the reviewer approves from that panel
- **THEN** the call to the service carries `APPROVE` and `version` 2, and the note if one was entered
- **AND** the response is `200`, and the request is shown as `APPROVED` with who decided and when

### Requirement: Explain a request that changed during review

The system SHALL present a `409` from a version-bound action — a decision, an
amendment or a withdrawal — as an explanation, not as a raw error: that the
request changed while it was open, what its current version and status now are,
and a way to reload it and review it again. The values SHALL come from the
service's response.

#### Scenario: A decision is refused as stale

- **WHEN** a reviewer with version 1 open submits a decision after the requester has amended the request to version 2
- **THEN** the service answers `409`
- **AND** the page explains that the request changed during review, states that the current version is 2, and offers to reload it
- **AND** no decision is recorded

#### Scenario: Reloading shows the current version

- **WHEN** the person chooses to reload after such an explanation
- **THEN** the detail view shows the request at version 2 and its history
- **AND** the decision panel now names version 2

#### Scenario: An amendment is refused as stale

- **WHEN** a requester with version 1 open submits an amendment after the request has moved to version 2 in another window
- **THEN** the service answers `409`
- **AND** the page explains the change in the same way and does not overwrite the newer version

### Requirement: Show discounts in basis points and percentage points

The system SHALL show every discount as basis points alongside the equivalent
percentage points, so that the unit is never ambiguous.

#### Scenario: A discount is shown in both units

- **WHEN** a request with a discount of 25 basis points is shown, in the queue or the detail view
- **THEN** it is shown as `25 bp` alongside `0.25 percentage points`

### Requirement: Submit the version being displayed when amending

The system SHALL send, with an amendment, the version the page is currently
displaying, so that two windows cannot silently overwrite each other.

#### Scenario: The amendment carries the displayed version

- **WHEN** a requester amends their `PENDING` request shown at version 1
- **THEN** the call to the service carries `version` 1 with the changed discount or reason
- **AND** the response is `200` and the page shows the request at version 2

#### Scenario: An amendment that changes nothing is left to the service

- **WHEN** a requester submits an amendment that changes neither the discount nor the reason
- **THEN** the page shows the `400` problem detail the service returns

### Requirement: Show the service's own words for every refusal

The system SHALL show, for every non-2xx response, the `title` and `detail` from
the service's problem detail body, and SHALL NOT write error text of its own. The
page SHALL NOT validate what the service validates, so that a value outside a
bound is refused by the service and explained in its words.

#### Scenario: A validation failure is shown as returned

- **WHEN** the person submits a discount of 500 basis points
- **THEN** the service answers `400`
- **AND** the page shows the `title` and `detail` of that response

#### Scenario: A refused decision is shown as returned

- **WHEN** a decision is refused with `403`, whether for the role or because the reviewer raised the request
- **THEN** the page shows the `title` and `detail` of that response

#### Scenario: The service cannot be reached

- **WHEN** a call fails before any response arrives
- **THEN** the page shows the failure the browser reports, without invented explanation

### Requirement: Create a request with one idempotency key per opening

The system SHALL let the person raise a request from an application identifier,
a discount in basis points and a reason. The page SHALL generate the
`Idempotency-Key` once when the form is opened and SHALL send that same key on
every attempt to submit it, so that a retry is a replay and not a second request.

#### Scenario: A request is created from the form

- **WHEN** the person submits a valid form
- **THEN** the service answers `201` with a `Location` header
- **AND** the page shows the new request as `PENDING` at version 1

#### Scenario: A retry reuses the key

- **WHEN** the person submits the form, does not see the response, and submits it again unchanged
- **THEN** both calls carry the same `Idempotency-Key`
- **AND** only one request exists

#### Scenario: A rejected submission keeps its key

- **WHEN** the service answers `400` and the person corrects the form and submits it again
- **THEN** the second call carries the same `Idempotency-Key` as the first

#### Scenario: Opening the form again starts a new key

- **WHEN** the person opens the create form after a previous one was completed or abandoned
- **THEN** a new `Idempotency-Key` is generated

### Requirement: Complete the workflow from the page alone

The system SHALL let a person create, amend and decide a request using the page
alone, switching identity between requester and reviewer, with no other client.

#### Scenario: A request is created, amended and approved

- **WHEN** a `RELATIONSHIP_MANAGER` creates a request, amends it, then the identity is switched to a `REVIEWER` who opens it and approves
- **THEN** the request is `APPROVED` at version 2
- **AND** its history shows the creation, the amendment and the decision, each with its version

#### Scenario: A request is created and withdrawn

- **WHEN** a `RELATIONSHIP_MANAGER` creates a request and then withdraws it
- **THEN** the request is `WITHDRAWN`
- **AND** it offers no further action
