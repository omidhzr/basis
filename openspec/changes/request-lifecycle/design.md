# Design

## Context

Greenfield: no project exists, so this change creates the skeleton as well as
the first behaviour. The technology is settled in CLAUDE.md and is not revisited
here.

Two constraints shape everything below. Invariant 7 requires the version and
status guards to live inside the `UPDATE` statement rather than in a read-then-
compare, because a read-then-write passes every single-threaded test and races
under concurrency. The testing section requires the state machine, the version
guard, the four-eyes rule and validation to be unit-testable in plain JUnit
with no Spring context. Taken together, these pull in opposite directions: one
puts enforcement in SQL, the other wants rules testable without a database.

## Goals / Non-Goals

**Goals:**

- A write path where no rejected call can leave a partial trace, and no two
  concurrent callers can both win.
- Domain rules readable and testable without a database or a Spring context.
- A structure the next three slices extend without rearranging.

**Non-Goals:**

- Idempotency, the read endpoints and the user interface. See proposal.md.
- Any abstraction over `JdbcClient`. Two tables, no associations.
- Optimistic-locking infrastructure. The version column is domain state that
  callers submit, not a framework concern.

## Decisions

### Guards are enforced in SQL; rules are evaluated in Java; they do not overlap

The two are split by what they protect against, not by preference.

*Enforced in the statement:* version match and current status. These race, so
they go in the `WHERE` clause of the guarded `UPDATE`, and `rowsAffected == 0`
is the rejection signal.

*Evaluated in Java:* the four-eyes rule, the role check, ownership of an amend
or withdrawal, and field validation. None of these races — they depend on the
caller and the submitted body, not on concurrent state — so evaluating them
before the write costs nothing and produces a far better error message.

The two sets are disjoint. No rule is checked in both places, which is what
would otherwise invite them to drift apart.

### A failed guarded write is diagnosed by a second read, used only for reporting

`rowsAffected == 0` is ambiguous: the request may not exist, or its version may
be stale, or it may be terminal. Those are `404` and `409` with different
detail text, so they must be told apart.

After a failed guarded write, the row is re-read to classify the failure. This
does not reintroduce the read-then-write hazard, because the write has already
happened or already failed — the re-read decides only what to *say*, never
whether to proceed. Recording the distinction explicitly because the pattern
looks, at a glance, like the thing invariant 7 forbids.

### Two classes on the write path, not three

`ExceptionRequestController` handles HTTP. `ExceptionRequestStore` holds the
SQL and owns the transaction. There is no service layer between them.

A transaction boundary is a real requirement — the guarded `UPDATE` and the
`request_history` `INSERT` must commit together — but it does not need a class
of its own. Each store method is one transactional unit corresponding to one
transition, which is the same granularity a service layer would have had.

The domain rules that a service layer would usually hold live on the records
and enums instead, as pure functions. That is what makes them unit-testable
with no Spring context, as the testing section requires.

### The history entry is written by the same method that made the transition

Not by an event listener, an `@EntityListener`, or an aspect. Invariant 3 makes
history the audit record; a mechanism that could be silently bypassed or fail
independently would undermine it. Two statements in one transactional method,
in the obvious order, is the form that can be read and verified.

### Identity is read with `@RequestHeader` parameters

No argument resolver, no filter, no security context. Two headers read where
they are used keeps the stub visible at every call site rather than hiding it
behind machinery that would have to be dismantled when real authentication
arrives.

### Request and response bodies are records, validated with Bean Validation

Bounds from CLAUDE.md's validation table are annotations on the request
records; the `@RestControllerAdvice` converts a binding failure into a `400`
problem detail. One advice class covers `400`, `403`, `404` and `409` for this
slice and every later one.

## Risks / Trade-offs

**The reported current version may itself be stale.** The diagnostic re-read is
not atomic with the failed write, so under sustained concurrency the version
quoted in a `409` can be out of date by the time the caller sees it. → Treat it
as advisory. The caller must re-fetch and re-review rather than resubmit with
the quoted version, and the detail text says so rather than implying the value
can be used directly.

**Append-only is a convention, not a constraint.** Nothing in H2 prevents an
`UPDATE` or `DELETE` against `request_history`; only code discipline does. → No
code path writes anything but an `INSERT`, and this is the first thing to check
if audit integrity is ever questioned. In production the application's database
role would lack `UPDATE` and `DELETE` on that table, which is where the
guarantee belongs.

**A high `409` rate would be invisible.** Stale-version rejections are the
signal that review is slower than amendment. Nothing measures them here, since
observability is out of scope. → Note it in the operational section of the
README: a rising `409` rate on decisions means reviewers are working from stale
queues, not that the service is malfunctioning.

**In-memory H2 loses everything on restart.** Deliberate, and the seed data
reloads. → Only a trade-off to state, not one to mitigate; it is the reason the
service can be run with nothing but a JDK.
