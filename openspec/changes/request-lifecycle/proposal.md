# Proposal

## Why

A relationship manager needs an authorised reviewer to approve a discount
before the mortgage process may use it. The brief carries a deliberate
conflict — the RM may change a submitted request, yet an approval must apply to
exactly what was reviewed — and the resolution chosen in CLAUDE.md, versioned
requests with version-bound decisions, only exists once the request aggregate
and its decision path do. Nothing is built yet, and every later slice reads,
displays or replays what this one produces.

## What Changes

- Create an exception request in `PENDING` at `version` 1, with a validated
  application identifier, a discount in basis points and a reason.
- Amend a request while `PENDING`. Each amendment increments `version` and
  appends a history entry. Only `discountBps` and `reason` may change.
- Decide a request, approve or decline, against an explicitly submitted
  `version`. A stale version is rejected with `409` and re-review is required.
- Withdraw a `PENDING` request. Requester only, version-bound like a decision.
- Enforce four-eyes server-side: the requester may not decide their own
  request.
- Append a row to `request_history` on every transition, recording the actor,
  the version the entry concerned, and the values it carried.
- Enforce the version and status guards inside the `UPDATE` statement, so
  concurrent callers cannot both win.
- Establish the project skeleton and the H2 schema the above needs. This
  carries no behaviour of its own and is included here only because there is
  no project yet to add behaviour to.

**Deliberately not in this change:** everything concerning `Idempotency-Key` —
the header requirement, the `400` when it is missing or blank, the `422` on a
replay carrying a different body, the `idempotency_record` table and the replay
contract itself. The read and query endpoints and the user interface are
likewise later slices.

Idempotency is kept whole rather than split at the validation boundary. Putting
the header check here and the replay contract in the next change would leave no
single spec stating what `POST /requests` requires, and would invite a reader to
mistake a validated header for an idempotent endpoint. The next slice adds all
of it at once, as a pure addition to this one.

## Capabilities

### New Capabilities

- `exception-request`: the life of a pricing exception request — creation,
  amendment, decision and withdrawal, the version guard that binds a decision
  to what was reviewed, the four-eyes rule, and the append-only history that
  answers what was approved, by whom, and when.

### Modified Capabilities

None. There are no existing specs; this is the project's first capability.

Later slices extend `exception-request` rather than introducing capabilities
beside it: idempotency and the read endpoints will each list it as a modified
capability and add requirements to it. Only the user interface earns a second
capability, because it is a different surface with a contract of its own. The
intent is recorded here so that a later change marking this capability
*modified* reads as the plan rather than as drift.

## Impact

**New project.** Java 17, Spring Boot 3 (web, jdbc, validation), H2 in-memory,
`springdoc-openapi`, Maven wrapper, and JUnit 5 with AssertJ and MockMvc for
tests. Every one of these is already fixed in CLAUDE.md's technology table; this
change introduces no dependency beyond it.

**New schema.** `exception_request` and `request_history`, created from
`schema.sql`. `idempotency_record` is deferred to the slice that uses it.

**New endpoints.** `POST /requests`, `PATCH /requests/{id}`,
`POST /requests/{id}/decision`, `POST /requests/{id}/withdrawal`. The `GET`
endpoints are deferred.

**Errors.** One `@RestControllerAdvice` producing RFC 7807 `ProblemDetail`
bodies, which every later slice reuses rather than adding its own.

**Identity.** Carried by the `X-User-Id` and `X-User-Role` headers, standing in
for JWT claims. Authorisation is enforced and tested; authentication is not
built.

**No breaking changes.** Nothing exists to break.
