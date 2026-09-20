# Proposal

## Why

A relationship manager submitting a request over a flaky connection cannot tell
a lost response from a lost request. Retrying is the only thing a client can
do, and today a retry raises a second request for the same discount on the same
application — two pending items in the reviewer's queue, one of which is an
artefact of the network rather than a decision anyone asked for. In a domain
where an approval is a commercial commitment, a duplicate is worse than an
error: it is a second thing that can be approved.

`request-lifecycle` deliberately left this whole: the header requirement, the
replay contract and the `idempotency_record` table were kept together rather
than split at the validation boundary, so that one spec states what
`POST /requests` requires. This is that slice, and it closes acceptance
criterion 2 — the only one of criteria 1–6 still unmet.

## What Changes

- **`POST /requests` requires an `Idempotency-Key` header.** Non-blank, at most
  128 characters. Missing or blank is a `400`, like any other validation
  failure, and creates nothing.
- **A replay returns the original response.** The same key from the same caller
  returns the status and body of the first call, unchanged — including the
  `201` and the `Location` header — and no second request is created, no second
  history entry appended.
- **The same key carrying a different body is a `422`.** A key that has already
  been used to create one request cannot be used to create a different one.
  This is the case that makes the mechanism worth having: it catches a client
  that reuses keys rather than silently accepting the second request or
  silently returning the first.
- **The key is scoped to the caller.** `idempotency_record` is keyed
  `(key, caller)`, so two relationship managers who happen to generate the same
  key do not collide. `caller` is the `X-User-Id` the request arrived with.
- **The guard is the unique constraint, not a prior read.** Per invariant 7,
  the insert is attempted and the constraint violation is caught; there is no
  "check whether this key exists, then insert". The same reasoning that put the
  version guard inside the `UPDATE` applies here, and for the same reason: a
  check-then-insert passes every single-threaded test and admits a duplicate
  under two concurrent retries, which is exactly the condition a retry storm
  produces.

**For design to settle, not decided here:**

- **How "a different body" is recognised.** CLAUDE.md fixes
  `idempotency_record` as `key, caller, request_id, response_body, status_code,
  created_at` — there is no column holding a fingerprint of the original
  request. Comparing against the created request's stored values, reached
  through `request_id`, appears to answer it within the fixed data model. The
  alternative, a fingerprint column, is a change to a settled model and should
  not be reached for first.
- **What a replay sees while the first call is still in flight.** Two retries
  can contend on the same key, and the loser catches the violation before the
  winner has committed the response it is supposed to return. This is the
  concurrent case the slice has to answer deliberately rather than discover.

**Deliberately not in this change.** A persistent idempotency store with a TTL
is out of scope and stays out: records live in the same in-memory H2 as
everything else and vanish on restart. Nothing expires or is swept — in
production a key would not be honoured indefinitely, and the retention period
is a business answer rather than a duration to invent. The read endpoints and
the user interface remain later slices; `PATCH` and the decision and withdrawal
endpoints are unaffected, being version-guarded already.

## Capabilities

### New Capabilities

None. Idempotency is a property of creating an exception request, not a
separate service.

### Modified Capabilities

- `exception-request`: adds requirements for the `Idempotency-Key` header on
  create, the replay contract, and the rejection of a reused key carrying a
  different body. This is the extension the archived `request-lifecycle`
  proposal anticipated rather than a later reinterpretation of it.

## Impact

**The capability path does not exist yet.** `request-lifecycle` was archived
without syncing its delta into `openspec/specs/`, so
`specs/exception-request/spec.md` has no current version to modify. The
requirements it established are intact in
`openspec/changes/archive/2026-09-20-request-lifecycle/`. Either that sync runs
before this change's spec delta is written, or the delta has to be framed
against a capability that exists only in the archive. `rejection-surface` is
waiting on the same thing.

**Schema.** Adds `idempotency_record` to `schema.sql` — the table
`request-lifecycle` deliberately deferred — with `(key, caller)` as its primary
key, which is also the constraint the write relies on.

**Code.** `POST /requests` in `ExceptionRequestController` gains the header;
the create path in `ExceptionRequestStore` gains the record and the caught
violation, inside the transaction that already writes the request and its
history entry. `ProblemDetailAdvice` gains the `422`, which is the first status
it answers beyond the four `request-lifecycle` established.

**Tests.** Acceptance criterion 2 becomes an integration test: replay returns
the original response and creates nothing new. The concurrent case needs the
same barrier-released contention `ConcurrentAmendmentTest` uses — two retries
of one create, exactly one request created.

**No new dependency.** The constraint, the catch and the table are all inside
the technology already fixed in CLAUDE.md.
