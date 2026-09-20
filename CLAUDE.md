# Basis — working context

**Basis** is an internal service for a financial-services client. A
relationship manager (RM) requests a discount on the standard interest rate for
a mortgage application; an authorised reviewer approves or declines it before
the application process may use it. The name is the unit the whole domain turns
on — a basis point — and the grounds on which a decision is made.

Repository `basis`, Maven artifactId `basis`, root package
`com.example.basis`. Keep those four in step, and keep the client's name out of
all of them.

This file is the standing context for any agent or engineer working in this
repository. Read it before changing code.

---

## Guiding constraint

The brief asks for **a small, clear solution with thoughtful decisions over a
broad but fragile implementation**. Scope discipline is a graded property here,
not a shortcut. Do not add dependencies, layers, or abstractions beyond what is
listed below without being asked.

---

## Domain invariants — do not violate

1. A request in a terminal state (`APPROVED`, `DECLINED`, `WITHDRAWN`) is
   **immutable**. Never add a code path that mutates one.
2. A decision **binds to an exact version** of the request. Deciding against a
   stale version is rejected with `409`.
3. Every state change appends a row to `request_history`. That table is
   **append-only** — never `UPDATE` or `DELETE` it.
4. The requester may not decide their own request (four-eyes).
5. The discount is stored as a **positive integer in basis points**. Never a
   float, never negative.
6. All timestamps are `Instant`, UTC.
7. **Guards are enforced in the write, not before it.** The version and status
   checks live inside the statement:
   `UPDATE exception_request SET ... WHERE id = ? AND version = ? AND status = 'PENDING'`,
   and `rowsAffected == 0` means `409`. Never read the row, compare in Java,
   then write — that passes every test and races under concurrency.
   Idempotency follows the same rule: insert against the `(key, caller)` unique
   constraint and catch the violation to return the stored response; never
   "check if it exists, then insert".

If a change appears to require breaking one of these, stop and raise it rather
than working around it.

---

## The contested requirement, and how it is resolved

The brief contains a deliberate conflict: one stakeholder wants the RM to be
able to change the requested discount after submitting; another wants an
approval to always apply to the exact request that was reviewed.

**Resolution: versioned requests, version-bound decisions.**

- A request may be amended **only while `PENDING`**. Each amendment increments
  `version` and appends a history entry.
- The reviewer submits the `version` they reviewed. If it no longer matches,
  the decision is rejected with `409` and re-review is required.
- After a decision the request is terminal. A later change means a **new**
  request for the same application; see the note on supersession below.

This satisfies both stakeholders and keeps the audit answer to "what exactly
was approved, by whom, and when?" unambiguous — which is the requirement that
actually matters in a regulated domain.

**Supersession — a deliberate simplification.** An application may end up with
more than one `APPROVED` request over time. Terminal requests are immutable, so
the earlier one is never marked superseded. The rule is instead: **the approved
request with the most recent `decided_at` is the one that applies**, and that is
what `GET /applications/{id}/approved-exception` returns. This is inferred
rather than recorded, which is the weaker choice; production would add a
`SUPERSEDED` status and a `superseded_by` link, guarded by a partial unique
index on `(application_id) WHERE status = 'APPROVED'`, so the chain is a fact
rather than a derivation. Say so in the README.

---

## Technology

| Area | Choice | Reason |
|---|---|---|
| Language | Java 17 | Runs on the reviewer's machine without a JDK upgrade |
| Framework | Spring Boot 3 | Client-standard stack |
| Persistence | `JdbcClient` + H2 in-memory, `schema.sql` | Two tables, no associations; explicit SQL keeps audit-critical writes visible |
| Build | Maven wrapper (`./mvnw`) | Only a JDK is required to run |
| API docs | `springdoc-openapi` | Browsable API at `/swagger-ui.html` |
| Errors | `ProblemDetail` (RFC 7807) | Structured errors via one `@RestControllerAdvice` |
| Tests | JUnit 5, AssertJ, MockMvc | Included in the starter |
| UI | Single static page, plain JavaScript + Pico.css (vendored) | No build step, no framework |

**Deliberately not used:** JPA/Hibernate (dirty checking conflicts with
immutability; mapping buys nothing at two tables), Lombok (records suffice),
Spring Security (identity is stubbed), messaging, Flyway, Testcontainers, and
any JavaScript framework.

---

## Data model

Two tables with different jobs. `exception_request` holds **what is true now** —
one row per request, updated in place — and is what the queue and the mortgage
process read. `request_history` holds **how it got there**: one row per
transition, `INSERT` only, never `UPDATE`d or `DELETE`d. The duplication is
deliberate; the current row is a cache of where the log ended up.

```
exception_request
  id (uuid)  application_id  discount_bps (int)  reason  status
  version (int)             -- the CURRENT version; a decision must match it
  requested_by
  decided_by                -- null while PENDING
  decided_at                -- null while PENDING
  created_at  updated_at

request_history            -- append-only
  id  request_id  entry_type
  version (int)            -- the version THIS entry concerned
  actor  payload  occurred_at
```

`payload` holds the values that entry concerned, so history stays readable
after the request row has moved on: the discount and reason for `CREATED` and
`AMENDED`, and the reviewer's optional free-text note for `APPROVED` and
`DECLINED`. The note has no column on `exception_request` — it belongs to the
decision, not to the request, and the decision is history.

```
idempotency_record
  key  caller  request_id  response_body  status_code  created_at
  primary key (key, caller)
```

## State machine

```
PENDING --amend--> PENDING (version + 1)
PENDING --approve--> APPROVED
PENDING --decline--> DECLINED
PENDING --withdraw--> WITHDRAWN
APPROVED | DECLINED | WITHDRAWN --> (terminal)
```

Illegal transitions return `409`.

## API

| Method | Path | Notes |
|---|---|---|
| POST | `/requests` | Requires `Idempotency-Key` header |
| PATCH | `/requests/{id}` | `{discountBps?, reason?, version}`. Amend while `PENDING`; `409` on version mismatch; increments version |
| POST | `/requests/{id}/decision` | `{decision, version, note?}`; `409` on version mismatch |
| POST | `/requests/{id}/withdrawal` | Requester only, while `PENDING`. `{version}`; `409` on version mismatch |
| GET | `/requests/{id}` | Current state + history |
| GET | `/requests` | Role-aware queue. Reviewer: `PENDING` requests raised by others (four-eyes filtering happens here, server-side). Requester: their own requests in all states. Optional `status` narrows either. |
| GET | `/applications/{id}/approved-exception` | What the mortgage process consumes |

Identity is carried by `X-User-Id` and `X-User-Role` headers, standing in for
claims that would come from a JWT in production. Say so in comments; do not
build authentication.

### Enumerations — use these exact values

| Set | Values |
|---|---|
| `X-User-Role` | `RELATIONSHIP_MANAGER`, `REVIEWER` |
| `status` | `PENDING`, `APPROVED`, `DECLINED`, `WITHDRAWN` |
| `entry_type` | `CREATED`, `AMENDED`, `APPROVED`, `DECLINED`, `WITHDRAWN` |
| `decision` in the decision body | `APPROVE`, `DECLINE` |

No others. Do not invent synonyms, abbreviations or lower-case variants.

### Validation

| Field | Rule |
|---|---|
| `applicationId` | Required, non-blank, max 64 chars. Not checked against any external system. |
| `discountBps` | Required, integer, `1..200` inclusive. The bound is a named constant, not a magic number. |
| `reason` | Required, non-blank, 10–500 chars. Short enough to be unhelpful is a validation failure, not a warning. |
| `version` in the decision and amend bodies | Required, positive integer |
| `Idempotency-Key` | Required on `POST /requests`, non-blank, max 128 chars |

Amend accepts `discountBps` and `reason`; every other field is immutable and a
request to change one is rejected.

### Status codes

| Situation | Code |
|---|---|
| Created | `201` with a `Location` header |
| Read, amended, decided, withdrawn | `200` |
| Idempotent replay | the original status and body, unchanged |
| Validation failure | `400` |
| Caller lacks the role for the action | `403` |
| Requester deciding their own request (four-eyes) | `403` |
| Unknown request id | `404` |
| Missing or blank `Idempotency-Key` | `400` |
| Same key replayed with a different body | `422` |
| Stale version, or illegal transition for the current status | `409` |

Every non-2xx response is a `ProblemDetail` body with a `title` and a `detail`
a person could act on — the 409 in particular states the current version.

---

## User interface

A single page served by the service itself from
`src/main/resources/static/index.html`, reached at `/`. No controller, no
routing, no build step, no JavaScript framework. Pico.css is **vendored** under
`static/vendor/` rather than loaded from a CDN, so the page renders on a machine
with no network access; it is classless, so the markup stays semantic HTML with
no class names. All behaviour is plain JavaScript using `fetch` and the DOM API.

The UI is a thin client over the same JSON API — it holds no business rules of
its own. Every invariant above is enforced server-side; the UI only reflects
what the API returns. Never implement a rule in JavaScript that the API does
not also enforce.

### Screens

Three sections of one page, toggled by state rather than routed. The screens
follow the request; the caller's role decides which actions are offered, and
the server decides which are permitted.

| Screen | Contents |
|---|---|
| Queue | Whatever `GET /requests` returns for the current caller — for a reviewer, pending requests raised by others; for a requester, their own in all states. Columns: application, discount in bp, reason, requester, status, submitted time, version. Each row opens the detail view. |
| Detail | The request's current values and its full history. Actions depend on the caller: a reviewer sees the decision panel, including an optional note stored with the decision; the requester sees amend and withdraw while the request is `PENDING`. A terminal request offers no actions to anyone. |
| Create | Application identifier, requested discount in basis points, reason. |

An identity switcher in the header sets the `X-User-Id` and `X-User-Role`
headers sent with every call. Switching between a relationship manager and a
reviewer is how the workflow is demonstrated — and it is visible in the queue,
which changes contents rather than merely hiding buttons.

### Required behaviours

1. The decision panel **names the version being decided** ("this decision is
   recorded against version 2"). This is the visible form of invariant 2.
2. A `409` from the decision endpoint is rendered as an explanation, not a raw
   error: the request changed during review, what the current version is, and a
   way to reload and review it.
3. Basis points are converted in view: `25 bp` shown alongside `0.25
   percentage points`, so the unit is never ambiguous.
4. Four-eyes is not a UI rule: a reviewer never sees their own requests in the
   queue because the API does not return them. If a request is reached
   directly, no approve control is offered, and a rejected attempt surfaces the
   reason returned by the API.
5. The amend control sends the version currently displayed, so two open tabs
   cannot silently overwrite each other.
6. Errors are read from the `ProblemDetail` body; never invent client-side
   error text.
7. The history is shown newest first, with the version each entry concerns.
8. The `Idempotency-Key` for a create is generated once when the form is
   opened and reused on every retry of that submission — never regenerated per
   click, which would defeat the mechanism from the client side.

---

## Acceptance criteria

These were written by hand before implementation. Tests must assert **what must
be true**, not how the current code happens to work.

1. An RM creates a request; it is `PENDING` with `version` 1 and a history entry is recorded.
2. Replaying the create with the same `Idempotency-Key` returns the original
   response and creates nothing new.
3. Amending a `PENDING` request increments its version; approving the previous
   version is rejected with `409`.
4. A reviewer approves the current version; the outcome records who and when.
5. An RM approving their own request is rejected.
6. The approved discount is retrievable for the mortgage application.
7. The page is served at `/` from a clean start with no build step and no
   network access.
8. The decision panel shows the version it is deciding, and a stale-version
   rejection is shown as an explanation of what changed rather than an error
   code.
9. Creating, amending and deciding a request can each be completed from the UI
   alone, switching identity between requester and reviewer.

---

## Testing

Two levels, chosen because the risk sits in two different places.

- **Unit tests on the domain** — the state machine, the version guard, the
  four-eyes rule, validation. Plain JUnit, no Spring context, fast. These cover
  the rules that make the service correct.
- **Integration tests through the API** — `@SpringBootTest` with MockMvc,
  against the real H2 schema. One per acceptance criterion 1–6, because the
  interesting behaviour (idempotent replay, stale-version rejection) only
  exists once persistence and HTTP are involved.

No mocking of the repository: the queries are the thing being tested. UI
criteria 7–9 are verified manually; say so rather than implying coverage.

---

## Deliverables

- `README.md` — the problem as understood, the contested requirement and how it
  was resolved, assumptions, deliberate omissions, what would change before
  production, and how to run, test and explore the service.
- **Seed data** loaded at startup (`data.sql`, dev profile): a handful of
  requests in different states — pending, amended to version 2, already
  approved, already declined — so a reviewer can exercise a realistic workflow
  without creating anything first.
- `scripts/demo.sh` and `scripts/demo.ps1` — the same scenario end to end, one
  for bash and one for PowerShell, so it runs without a shell prerequisite on
  either macOS/Linux or Windows. Both narrate each step and its result: create,
  retry with the same idempotency key, amend, attempt a stale-version approval
  (409), approve the current version, attempt self-approval, then read the
  approved discount. Keep the two in step — a change to one is a change to
  both. Commit `demo.sh` with the executable bit set and LF line endings
  (`*.sh text eol=lf` in `.gitattributes`).
- **A state diagram** in the README (Mermaid) plus a short note on the data
  model, so another engineer can pick the work up.
- **Operational notes** — what to check if the service misbehaves, and what the
  409 rate would mean in production.

---

## Assumptions (record in README)

- Mortgage applications and users are plain strings the service stores and
  returns without interpreting them. There are no tables for either, and an
  application identifier is not checked against any real system, so a
  non-existent one would be accepted. In production it would be validated
  against the mortgage system.
- An application may accumulate more than one approved exception. The one with
  the most recent `decided_at` applies. Supersession is not modelled — see the
  note under the contested requirement.
- Discount bounded at 1–200 bps as a named constant. The real threshold is a
  policy decision, not a known business rule.
- Retention, availability and data-protection requirements were not specified
  and are not invented.
- **An approved exception does not expire.** Once approved, the discount stays
  available to the application process indefinitely. Nothing in the brief
  suggests a validity period, but an indefinite discount is a commercial
  exposure — flag it as a question for the business rather than inventing a
  duration.
- **A reviewer cannot counter-offer.** Approve means approve the requested
  amount exactly; there is no "approve at 25 instead of 40". Allowing the
  reviewer to change the amount would make him the requester and break
  four-eyes. A lower amount therefore requires a decline and a new request,
  which loses the link between the two — also a question for the business.

## Out of scope — state in the README, do not build

Real authentication (OIDC/Entra ID), Postgres + Flyway, Testcontainers, event
publishing to downstream consumers via a transactional outbox, persistent
idempotency store with TTL, observability.

---

## Working agreement

- Commit in small steps with honest messages; the history is part of what is reviewed.
- Comments explain **why**, not what.
- Do not add a dependency without stating the reason. This includes front-end
  assets: nothing is loaded from a CDN, and nothing introduces a build step.
- The UI is built last, after the API and its tests are green. If time runs
  short it is dropped and said so in the README — never at the cost of the
  service or the decision record.
- Do not generate tests and implementation from the same prompt in one pass —
  the tests would encode the same misreading as the code. Implement against the
  acceptance criteria above, then review each test against its criterion.
- Anything an agent produces must be read and understood before it is committed.
