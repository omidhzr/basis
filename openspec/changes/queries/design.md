# Design

## Context

See proposal.md for motivation. Everything here reads the two tables that
already exist; no statement in this change writes anything, so the invariant
about guards living inside the write does not come up — there are no guards to
place. The constraint that does bind is that a read must not become a second
way of expressing a rule: four-eyes is already enforced when a decision is
attempted, and the queue's exclusion of the caller's own requests is the same
rule made visible, not a new one.

## Goals / Non-Goals

**Goals:**

- The mortgage process can consume an approved discount without knowing how
  supersession is resolved.
- A reviewer's queue cannot contain their own request, whatever the caller does.
- History has one order, and it is the order the request actually moved through.

**Non-Goals:**

- A read model, a projection, or any separation between the shape written and
  the shape read. Two tables and a handful of queries do not earn one.
- Caching. The queue is a query against an in-memory database.

## Decisions

### History is ordered by version first, then by time, then by transition

`ORDER BY version DESC, occurred_at DESC, <a decision after the entry it
reviewed> DESC, id DESC`, replacing the `occurred_at DESC, version DESC` the
append-only trail is read with today.

Version is the thing the domain increments, and it never decreases: an
amendment raises it, and every later entry carries at least the version of the
one before. Ordering by it first means the sequence shown to a reviewer follows
the versions the request moved through, and the clock is consulted only to
separate two entries *within* one version — an amendment to version 2 and the
decision recorded against version 2.

The clock alone does not separate that pair. `Instant.now()` is granular to a
millisecond or worse on some hosts, and an amendment followed by the decision
reviewing it can land in one tick, which made the order of the two a coin-toss
decided by a random UUID — the integration test written for this scenario
caught it. So the transition itself breaks the tie: a terminal entry sorts
after the `CREATED` or `AMENDED` entry of the same version, and the state
machine guarantees there is at most one such pair, because a version admits one
decision and a request that has been decided admits no further entries. That
makes the order total on domain facts rather than on timing.

The reverse — time first, version second — was what the code did and what this
change was going to keep. It makes the order depend on clock precision in the
one case where precision is least trustworthy: two transitions recorded in the
same tick. `id DESC` remains as a final tiebreaker so that repeated reads agree
with each other, which is a weaker promise than correctness and is all the last
position needs to carry.

*Alternative considered:* a generated always-increasing column on
`request_history`, ordered by append order. It answers the same-instant case
exactly rather than stably, and an append-only log ordered by when it was
appended is an honest design. Rejected because `request_history` has the columns
CLAUDE.md's data model names, and the version and the transition together
already break every tie the state machine can produce, so the column would be a
schema change bought with nothing. The ordering above depends on domain data
rather than on a database feature.

### The queue is two statements chosen by role, not one statement with a role in it

A reviewer's queue and a requester's queue select different rows for different
reasons — one excludes the caller, the other requires them — and a single query
carrying both as predicates would read as a puzzle. Each is written out, and the
optional status is applied to both as `(? IS NULL OR status = ?)` so that the
narrowing is one expression rather than two assembled strings.

The reviewer's statement excludes `requested_by = ?` in the `WHERE` clause. That
is four-eyes expressed as a query, and it is the reason the rule holds even
though nothing in the UI is asked to hide anything: `GET /requests` cannot
return a request the caller raised, so no client can display one.

Absent a status, a reviewer's queue is `PENDING` only; a status given
explicitly replaces that default rather than narrowing within it, so a reviewer
can ask what was approved. The exclusion of their own requests is not part of
what a status can change.

### Supersession is resolved in the query, not in Java

`WHERE application_id = ? AND status = 'APPROVED' ORDER BY decided_at DESC
FETCH FIRST 1 ROW ONLY`. The rule that the most recent approval applies is
recorded in one statement, which is where a reader looking for it will go. The
alternative — read every approved request and pick in Java — moves a business
rule into a loop and makes the endpoint's cost grow with an application's
history.

This is the inferred supersession CLAUDE.md records as the weaker choice. The
query is where that weakness lives, and a comment says so, so that the change
to a `SUPERSEDED` status and a partial unique index has an obvious home.

### A request and its history travel in one response

One shape carrying the request's current values and its entries, returned by
`GET /requests/{id}`. `RequestBodies.View` deliberately does not carry history —
its comment says the read slice will decide that — so the shape is added beside
it rather than by widening it, and the write endpoints keep returning what they
return today.

Each entry carries its `payload` as the JSON object it was stored as, not as an
escaped string. A client that has to parse a string out of a parsed document is
being made to do the service's job.

### An unparseable status is already a 400

`status` binds to the `RequestStatus` enum, so a value outside the four raises
the type-mismatch the advice already answers as `400` with a problem detail.
Nothing is added for it; the spec scenario asserts the behaviour that falls out.

## Risks / Trade-offs

**The queue returns everything that matches.** A requester with years of
requests, or a quiet reviewer queue that has grown, is one unbounded response. →
Accepted for a demonstration service against an in-memory database, and named in
the proposal as deliberately out of scope. The shape a real volume needs is a
decision for a real volume; the honest note belongs in the README's operational
section rather than in an invented cursor.

**Reading by identifier is unrestricted.** Any caller who knows an identifier
reads the request, its history and the reviewer's note. → The open question in
the proposal. The alternative restricts to requester-or-reviewer, which in a
two-role service restricts nothing, so the check would be cost without effect
until there is a third role or a privacy rule.

**The approved-exception endpoint answers from `decided_at`.** Two approvals
sharing a `decided_at` to the microsecond would make "the most recent" a
coin-toss. → Two approvals on one application are already an unusual state, and
two in the same tick require two reviewers deciding simultaneously on requests
that should not both exist. Recorded here rather than guarded, because the
guard that would fix it properly is the `SUPERSEDED` status this service
deliberately does not model. The integration test for the rule waits for the
clock to move between its two approvals rather than pretending the tie is
resolved, so what it asserts is the rule and not a coin-toss.

## Open Questions

None. The three points the brief left undecided were settled before the
proposal was written and are recorded there; the one remaining question — who
may read a request by identifier — does not change these specs, this approach
or the tasks, because the permissive reading is what they describe and a
restriction would be a later change rather than a revision of this one.
