# Proposal

## Why

Verification of `request-lifecycle` found the service answers every rejection
it anticipates, and answers three it does not: a call the framework refuses
before a handler is reached, an identity header longer than the column that
stores it, and an amendment that carries no field to amend. The first two are
already promised by the requirement *Report errors as problem details* — "every
non-2xx outcome" — so this is a gap against a requirement that exists rather
than a new ambition. The third lets a caller spend a version, and invalidate a
reviewer's in-flight decision, while recording nothing that explains why.

Raised now rather than fixed inside `request-lifecycle` because that change is
complete and its tests are green: these are a small, separable slice, and
folding them in would make a verified change unverified again.

## What Changes

- **Every rejection is a problem detail, including the ones the framework
  raises.** An unsupported content type, an unsupported method and an unknown
  path currently return Spring's default error body — `application/json` with
  `timestamp`/`error`/`path`, no `title`, no `detail`. They must carry the same
  RFC 7807 shape as every other rejection.
- **A caller-supplied identity that cannot be stored is a `400`, not a `500`.**
  `X-User-Id` is written to a `VARCHAR(128)` column unchecked, so a longer
  value reaches the database and fails there. The bound already exists twice —
  the column, and the unused `RequestLimits.MAX_IDENTITY_LENGTH` — and needs to
  be enforced once, at the edge.
- **An amendment must carry something to amend.** `PATCH {"version": n}` with
  neither `discountBps` nor `reason` currently succeeds: version `n` → `n+1`, no
  value changed, an `AMENDED` history entry with an empty payload. It should be
  rejected with `400`.

**Deliberately not in this change**, recorded so they are not lost:

- `schema.sql` restates the discount bound `1..200` as literals while
  `RequestLimits` claims to be the single place it lives. A comment or a test,
  not a mechanism.
- `request_history.payload` is `VARCHAR(1024)`; a 500-character reason made
  entirely of characters JSON escapes serialises past it. Margin, not a defect
  seen in practice.
- History is ordered by `occurred_at DESC, version DESC`, which leaves two
  entries sharing both values unordered. It matters when the UI slice depends
  on "newest first", not before.

**Two questions for the business, per CLAUDE.md's precedence rule.** Neither is
invented here; both are points the brief does not decide:

- CLAUDE.md's validation table does not bound `X-User-Id`. The 128 characters
  proposed is inherited from the column width, not from a stated rule. A real
  identity provider would settle it.
- Whether a no-op amendment should be refused, or accepted as a deliberate way
  to force re-review. This change proposes refusing it, the restrictive
  reading: relaxing it later is safe, and nothing in the brief asks for a
  version bump that changes nothing.

## Capabilities

### New Capabilities

None. Every change here is a rejection the `exception-request` capability
already owns.

### Modified Capabilities

- `exception-request`: adds scenarios for a rejection raised before a handler
  is reached and for an identity header that exceeds its bound, both under the
  existing *Report errors as problem details* requirement; and a scenario under
  *Amend a pending request* refusing an amendment that carries no field.

## Impact

**Depends on `request-lifecycle` being archived first.** `openspec/specs/` is
empty until then, so the capability path this change modifies does not yet
exist.

**Code.** `ProblemDetailAdvice` (the framework rejections),
`ExceptionRequestController` (the identity bound, at each call site where the
headers are read), `RequestBodies.Amend` (a body carrying at least one field).
`application.properties` if the framework rejections are covered by enabling
Spring's own problem details rather than by handling each exception.

**Tests.** `ExceptionRequestApiTest.everyRejectionIsAProblemDetail` asserts the
blanket claim using only rejections the advice already handles, so it passes
today and would pass unchanged after this work; it needs the 415 and 405 cases
added or the claim is still untested. `RequestValidationTest` asserts either
amend field may be omitted, which stays true, and needs the case where both
are.

**No new dependency.** Nothing here reaches beyond the technology already
fixed in CLAUDE.md.
