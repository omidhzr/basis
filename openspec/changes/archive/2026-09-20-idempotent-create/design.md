# Design

## Context

See proposal.md for motivation. The create path already exists: one
transactional method inserts the request row and its `CREATED` history entry
together. This change adds a third write to that transaction and a replay path
around it.

Two constraints shape everything below. Invariant 7 requires the guard to be
the unique constraint itself — insert and catch the violation, never check then
insert. And CLAUDE.md fixes `idempotency_record` as
`key, caller, request_id, response_body, status_code, created_at`, with no
column holding a fingerprint of the original request, which is what makes
recognising "a different body" the interesting question rather than an obvious
one.

Two facts were established by experiment against H2 2.3.232 rather than
assumed, because the whole replay contract rests on them. Both are recorded
under Decisions with what was observed.

## Goals / Non-Goals

**Goals:**

- A retry that reaches the service twice produces one request, whether the
  second arrival is a second later or concurrent with the first.
- A replay returns the response the first call returned, not a response
  reconstructed from current state.
- The mechanism costs the non-retry path one insert and no reads.

**Non-Goals:**

- Idempotency on any endpoint but create. The others are version-guarded, which
  answers the same question a different way.
- Expiry, sweeping or a TTL. Out of scope in CLAUDE.md and stated as an
  omission, not an oversight.
- Any abstraction over the existing store. This is a third statement in a
  method that already writes two.

## Decisions

### `key` is quoted in the schema, because H2 2.x reserves it

`CREATE TABLE idempotency_record (key VARCHAR(128), ...)` fails outright on H2
2.3.232 — `Syntax error ... expected "identifier"`. `KEY` is a reserved word in
H2 2.x, where it was usable in 1.x.

CLAUDE.md names the column `key`, and CLAUDE.md is the source of truth, so the
column keeps that name and the schema quotes it: `"key" VARCHAR(128)`. Every
statement touching it quotes it too. Renaming the column to `idempotency_key`
would read more naturally and avoid the quoting, but it would put the schema
and the documented data model out of step, which is the worse trade. Recorded
here because a quoted identifier is otherwise the kind of thing a later reader
tidies away and breaks.

### A differing body is recognised from the stored response, not the current request row

The obvious reading — follow `request_id` to the `exception_request` row and
compare the caller's body against what is stored there — is wrong, and quietly
so. A request can be amended after it is created. An RM who creates at 25 bps,
amends to 40, and then retries the original create would have their retry
compared against 40, differ from it, and receive `422` for a body that is
exactly what they first sent.

`response_body` does not move. It is the `201` body as returned, frozen at
creation, so comparing against the create fields it carries — application
identifier, discount, reason — compares against what was actually submitted.
It also needs no second query: the row fetched to perform the replay is the row
the comparison reads.

The comparison is on those three fields, parsed, not on the raw bytes. Two
bodies differing only in whitespace or field order describe the same request
and are a replay, not a `422`.

*Alternative considered:* a fingerprint column on `idempotency_record`. It is
the cleaner mechanism — it compares the request against the request — but the
table's columns are fixed in CLAUDE.md, and a change to a settled data model is
not something this slice should reach for first when an answer exists inside
it.

### The idempotency record is written last, from the response that is returned

Ordering within the transaction: request row, history entry, idempotency
record. The record is built from the same value the endpoint returns, so
"the stored response is the response the caller received" is true by
construction rather than by two pieces of code agreeing.

Writing the record first would let a duplicate block earlier and waste less
work on the losing path, and it is possible — the response body is fully
determined before any insert. It is not worth it: it would mean serialising a
response that has not been produced yet, and the losing path is the rare one.

Because the request row is written first, `request_id` may carry a foreign key
to `exception_request`, as `request_history` does.

### A losing retry replays after its own transaction rolls back

Established by experiment, not assumed. Two connections at READ_COMMITTED
inserting the same primary key on H2 2.3.232:

- The second insert **blocks** until the first transaction ends. It does not
  fail immediately.
- If the first **commits**, the second then fails with `23505`, and after
  rolling back it **can read the committed record** — so there is always
  something to replay.
- If the first **rolls back**, the second insert **succeeds**, and that caller
  becomes the creator.

This is what makes the contract answerable without inventing a status code for
"the request you are retrying is still in flight". There is no window in which
a caller is told a key is taken but cannot be shown what it produced.

The consequence for the code: the violation poisons the transaction, so the
replay read happens in a *new* one, after rollback. That differs from the
existing `classify()` on the amend and decide paths, where the failed `UPDATE`
leaves the transaction usable and the re-read happens inside it. Same shape,
different reason — worth stating because the two will sit next to each other.

### The key is read and validated where the other headers are

`@RequestHeader("Idempotency-Key")` on the create method, beside `X-User-Id`
and `X-User-Role`, validated to the bounds in CLAUDE.md's table. A missing
header is already a `400` through the existing advice; blank and over-length
join the other validation failures. No filter, no interceptor: the endpoint
that requires the key is the endpoint that reads it.

`caller` is the `X-User-Id` the create arrived with, which is what makes
`(key, caller)` the primary key rather than `key` alone.

## Risks / Trade-offs

**The lock wait is bounded by H2's lock timeout.** A losing retry waits for the
winning transaction, and if that wait exceeds the configured timeout it gets a
timeout error rather than the replay. In the experiment a 1.5-second hold was
waited out without timing out, and a real create transaction is three inserts.
→ Left as is. If creates ever become slow enough for this to matter, the
timeout is a setting, and a create that slow is the problem worth fixing.

**The comparison depends on the response body carrying the create fields.** It
does today, and if the response shape ever stops echoing the discount or the
reason, the `422` check silently weakens to comparing fewer fields — it would
not fail, it would just stop catching reuse. → A test asserting that a reused
key with each field changed in turn is rejected, so the weakening shows up as a
failure rather than as a gap.

**Keys do not survive a restart.** The records live in the same in-memory H2 as
everything else, so a retry that arrives after a restart creates a second
request rather than replaying the first. → Inherent to the chosen persistence
and already stated as an omission; the production answer is the persistent
store with a TTL that CLAUDE.md puts out of scope.

**Nothing bounds the table's growth.** One row per create, forever, with no
sweep. → Acceptable for an in-memory service that is restarted to empty it;
named in the README as part of what the persistent store would have to solve.
