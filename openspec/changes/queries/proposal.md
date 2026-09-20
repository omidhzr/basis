# Proposal

## Why

The service records requests, amends them and decides them, and then cannot be
asked about any of it. Acceptance criterion 6 — the approved discount is
retrievable for the mortgage application — has no endpoint behind it, and the
integration tests reach into `ExceptionRequestStore` to assert what was stored,
each carrying a comment that the read endpoints belong to a later slice. This is
that slice. Until it exists there is nothing for the UI to be a thin client
over, and the mortgage process, the consumer the whole service exists to serve,
cannot consume anything.

## What Changes

- **A request can be read by identifier**, returning its current values together
  with its full history, newest first. The history is what makes the audit
  answer — what was approved, by whom, and when — available to anyone other than
  a person with a SQL prompt.
- **The queue is role-aware and filtered server-side.** A reviewer is offered
  `PENDING` requests raised by others; four-eyes is enforced by the query, not
  by the caller asking nicely. A requester sees their own requests in every
  state. An optional `status` narrows either.
- **The approved exception for an application is retrievable**, which is
  acceptance criterion 6 and the only endpoint a system rather than a person
  calls. Where an application has accumulated more than one approved request,
  the one with the most recent `decided_at` is returned, as the settled
  supersession rule requires.
- **History ordering becomes a promise rather than an accident.** Entries are
  ordered `occurred_at DESC, version DESC` today, which leaves two entries
  sharing both values in whatever order the database returns them. The
  `rejection-surface` proposal recorded this as mattering "when the UI slice
  depends on newest first, not before". A `CREATED` and an `AMENDED` entry
  written in the same millisecond is not hypothetical on a fast machine, so the
  ordering needs a deterministic tiebreaker before anything is promised.

**Deliberately not in this change**, recorded so they are not mistaken for
oversights:

- **Pagination.** The queue returns every matching request. At the volume a
  single reviewer's queue reaches this is not a problem worth a cursor, and
  inventing one now would fix a shape that a real volume should choose.
- **Filtering the queue by application.** Nothing in the brief asks to list a
  single application's requests, and the approved-exception endpoint already
  answers the question the mortgage process actually has.
- **A history endpoint of its own.** History travels with the request it belongs
  to. Splitting it would invite a client to read the two separately and render a
  request beside a history that has moved on.

## Capabilities

### New Capabilities

None. Reading a request is the same capability as raising one, and splitting
the read side out would leave two specs describing one service.

### Modified Capabilities

- `exception-request`: adds requirements for reading a request with its
  history, for the role-aware queue including the four-eyes filter, and for
  retrieving an application's approved exception. Adds a scenario to the
  existing append-only history requirement fixing the order entries are
  returned in, which is currently unstated and therefore untestable.

## Impact

**Code.** `ExceptionRequestStore` gains the queries and a deterministic order on
the existing `historyOf`. `ExceptionRequestController` gains three `@GetMapping`
methods. `RequestBodies` gains the shape that carries a request together with
its history — `View` cannot, and deliberately says so in a comment written when
this slice was still ahead.

**Tests.** The comments in `ExceptionRequestApiTest` and `IdempotentCreateTest`
explaining that history is read through the store "because the read endpoints
belong to a later slice" stop being true. The existing assertions stay as they
are — they assert what was stored, which is still worth asserting directly —
but the comments need correcting rather than leaving to mislead the next reader.

**No new dependency, and no new table.** Every query runs against the two tables
that already exist.

## Decisions taken by the business

Three points the brief leaves undecided, settled before this was written so that
nothing here is invented:

- **An application with no approved exception answers `404`**, with a problem
  detail, rather than `200` and an empty body. An absent resource is a `404`
  everywhere else in this API, and a consumer that cannot tell "nothing
  approved" from "application unknown" will eventually treat one as the other.
- **Every read carries identity**, as every write does. The approved-exception
  endpoint is called by a system rather than a person and still sends the
  headers: one rule about identity is easier to keep true than one rule with an
  exception, and it leaves the audit story intact when real authentication
  replaces the stub.
- **An explicit `status` widens the reviewer's queue** to requests of that
  status raised by others, rather than narrowing within `PENDING`. A reviewer
  asking to see what was approved is asking a reasonable question, and the
  four-eyes filter — never your own requests — is what must hold, not the
  status.

## Open question for the business

- **Any caller may read any request by identifier.** The queue is the only
  role-aware read; a requester who knows an identifier can read a colleague's
  request, including its history and the reviewer's note. The permissive reading
  is built because the alternative — requester or reviewer only — is every role
  this service has, and so restricts nothing while adding a check. If pricing
  exceptions are meant to be private between their requester and their reviewer,
  that is a rule to add here rather than in the UI.
