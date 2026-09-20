# Basis

A service for pricing exceptions on mortgage applications. A relationship
manager asks for a discount on the standard interest rate; an authorised
reviewer approves or declines it before the application process may use it.

The name is the unit the domain turns on — a basis point — and the grounds on
which a decision is made.

---

## State of this repository

**Read this first.** The service is built in slices, and not all of them are
done. What follows is accurate as of the last commit.

| Area | State |
|---|---|
| Create, amend, decide, withdraw | Built, tested |
| Version-bound decisions, four-eyes, append-only history | Built, tested |
| Idempotent create | Built, tested |
| Every rejection as a problem detail; bounded identity | Built; its integration tests are not written yet |
| Read endpoints — `GET /requests/{id}`, `GET /requests`, `GET /applications/{id}/approved-exception` | **Not built** |
| User interface | **Not built** |
| Seed data, demo scripts | **Not built** |

49 tests, green. Against the acceptance criteria in `CLAUDE.md`: 1–5 are met;
6 needs the read endpoints; 7–9 need the user interface.

The read endpoints are the most conspicuous gap — the mortgage process has
nowhere yet to ask what was approved. They are a planned slice, not an
oversight, and the write path they read from is finished and does not change
when they are added.

---

## Running it

A JDK 17 is the only prerequisite. The Maven wrapper fetches everything else,
and the database is in-memory.

```bash
./mvnw spring-boot:run          # http://localhost:8080
./mvnw test                     # 49 tests
```

Explore the API at <http://localhost:8080/swagger-ui.html>.

There is no seed data yet, so start by creating a request:

```bash
curl -i -X POST http://localhost:8080/requests \
  -H 'X-User-Id: rm-1' -H 'X-User-Role: RELATIONSHIP_MANAGER' \
  -H 'Idempotency-Key: demo-1' -H 'Content-Type: application/json' \
  -d '{"applicationId":"APP-1","discountBps":25,
       "reason":"Retention case for a long-standing client"}'
```

Send it a second time with the same key: the same `201`, the same body, the
same request. Change the discount and send it again with that key still: `422`.

Then approve it as someone else — the requester may not decide their own
request:

```bash
curl -i -X POST http://localhost:8080/requests/{id}/decision \
  -H 'X-User-Id: reviewer-1' -H 'X-User-Role: REVIEWER' \
  -H 'Content-Type: application/json' \
  -d '{"decision":"APPROVE","version":1,"note":"Within delegated authority"}'
```

Everything is in memory, so restarting empties it.

---

## The problem as understood

A discount on the standard rate is a commercial commitment, so someone other
than the person asking for it has to agree. The service exists to make that
agreement auditable: at any later date it must be able to answer **what was
approved, by whom, and when** — without ambiguity, and without depending on
anyone's memory of what the request said at the time.

That last clause is what makes this more than a form with an approve button.

## The contested requirement, and how it is resolved

The brief pulls in two directions. One stakeholder wants the relationship
manager to be able to change a request after submitting it. Another wants an
approval to apply to exactly the request that was reviewed. Taken literally,
both cannot hold: if the request can move, an approval recorded against "the
request" means nothing.

**Resolution: versioned requests, version-bound decisions.**

- A request may be amended only while `PENDING`. Each amendment increments its
  `version` and appends a history entry.
- A reviewer submits the `version` they reviewed. If it is no longer current,
  the decision is rejected with `409` and the request must be reviewed again.
- After a decision the request is terminal and never changes.

Both stakeholders get what they asked for, and the audit question stays
answerable: an approval names a version, and that version's contents are in the
history. Nobody has to reconstruct what the request said last Tuesday.

The cost is a reviewer who occasionally has to look again. That is the correct
cost — the alternative is approving something that has since changed.

---

## How it works

### States

```mermaid
stateDiagram-v2
    [*] --> PENDING: create
    PENDING --> PENDING: amend (version + 1)
    PENDING --> APPROVED: approve
    PENDING --> DECLINED: decline
    PENDING --> WITHDRAWN: withdraw
    APPROVED --> [*]
    DECLINED --> [*]
    WITHDRAWN --> [*]
```

Every illegal transition answers `409`. Terminal means terminal: no code path
mutates an `APPROVED`, `DECLINED` or `WITHDRAWN` request.

### The two tables

`exception_request` holds **what is true now** — one row per request, updated in
place. `request_history` holds **how it got there** — one row per transition,
inserted and never updated or deleted. The duplication is deliberate: the
current row is a cache of where the log ended up.

Each history entry records the transition, the actor, the version that entry
concerned, and a payload of the values it carried — the discount and reason for
`CREATED` and `AMENDED`, the reviewer's note for a decision. The note has no
column on the request because it belongs to the decision, not to the request,
and the decision is history.

`idempotency_record` holds one row per create, keyed `(key, caller)`, carrying
the response that create produced.

### The guard

This is the decision the rest of the design follows from.

The version and status checks live **inside** the write:

```sql
UPDATE exception_request
   SET ...
 WHERE id = ? AND version = ? AND status = 'PENDING'
```

`rowsAffected == 0` means the request moved, and the caller gets `409`.

The alternative — read the row, compare in Java, then write — passes every
single-threaded test and loses races. Two reviewers acting on version 2 would
both read version 2, both find it current, and both write. `ConcurrentAmendmentTest`
exists to fail if the guard is ever moved out of the statement.

Idempotency follows the same rule: the create inserts against the `(key, caller)`
constraint and catches the violation. It never checks whether the key exists
first.

Rules that *cannot* race — four-eyes, the reviewer role, ownership of an amend
or withdrawal, field validation — are evaluated in Java before the write, where
they produce a much better error message. The two sets are disjoint; no rule is
enforced in both places.

### A detail worth knowing

A retry is recognised by comparing against the **stored response**, not against
the request row. The row moves when the request is amended, so an RM who
creates at 25 bps, amends to 40, and then retries the original create would
otherwise be told `422` for the body they first sent. The stored response is
frozen at creation and is the only record of what was originally submitted.

---

## API

Identity is carried by `X-User-Id` and `X-User-Role`, standing in for claims
that a JWT would carry in production. **Nothing verifies them.** Authorisation
is real and tested; authentication is not built.

| Method | Path | Notes |
|---|---|---|
| POST | `/requests` | Requires `Idempotency-Key`. `201` with `Location` |
| PATCH | `/requests/{id}` | `{discountBps?, reason?, version}` — at least one of the two. Requester only, while `PENDING` |
| POST | `/requests/{id}/decision` | `{decision, version, note?}`. Reviewer only, not the requester |
| POST | `/requests/{id}/withdrawal` | `{version}`. Requester only |

| Situation | Code |
|---|---|
| Created | `201` + `Location` |
| Amended, decided, withdrawn | `200` |
| Idempotent replay | the original status and body, unchanged |
| Validation failure, missing or blank `Idempotency-Key` | `400` |
| Wrong role, or the requester deciding their own request | `403` |
| Unknown request | `404` |
| Stale version, or an illegal transition | `409` |
| A key replayed with a different body | `422` |

Every non-2xx response is an RFC 7807 problem detail with a title and a detail
a person can act on. A `409` states the current version and says to re-read the
request rather than resubmit with the number it just quoted — that number is
advisory and may itself be stale by the time the caller sees it.

---

## Assumptions

- **Applications and users are opaque strings.** The service stores and returns
  them without interpreting them. There are no tables for either, and an
  application identifier is not checked against anything, so a non-existent one
  is accepted. In production it would be validated against the mortgage system.
- **An application may accumulate more than one approved exception.** The one
  with the most recent `decided_at` applies. Supersession is not modelled —
  terminal requests are immutable, so an earlier approval is never marked
  superseded, and the rule is inferred rather than recorded. Production would
  add a `SUPERSEDED` status and a `superseded_by` link, guarded by a partial
  unique index on `(application_id) WHERE status = 'APPROVED'`, so the chain is
  a fact rather than a derivation. This is the weaker choice and is called out
  deliberately.
- **The discount is bounded at 1–200 basis points**, as a named constant. The
  real threshold is a policy decision, not a known business rule.
- **Retention, availability and data-protection requirements were not
  specified** and have not been invented.

## Open questions for the business

Points where the brief does not decide and the service had to. Each states the
behaviour built, so nothing is silently undefined. None is a technical choice.

- **An approved exception does not expire.** Once approved, the discount stays
  available indefinitely. Nothing suggests a validity period, but an indefinite
  discount is a commercial exposure — a question for the business rather than a
  duration to invent.
- **A reviewer cannot counter-offer.** Approve means approve the requested
  amount exactly. Letting the reviewer change the amount would make them the
  requester and break four-eyes, so a lower amount requires a decline and a new
  request — which loses the link between the two.
- **Anyone may raise a request, including a reviewer.** Nothing restricts
  creation to a relationship manager, and four-eyes still stops a reviewer
  deciding their own. If raising a request is meant to be an RM's act alone,
  that is a role check to add.
- **Only the requester may amend.** A third party's amendment is rejected,
  matching withdrawal. The restrictive reading is deliberate: relaxing it later
  is safe, tightening it later invalidates requests already amended.
- **An amendment must change something.** A `PATCH` carrying only a version is
  rejected, because a version bump that alters nothing still forces a reviewer
  to start again. This removes a possible way to deliberately force re-review;
  withdrawal already exists for taking a request back.
- **`X-User-Id` is bounded at 128 characters.** That number comes from the
  column width, not from any stated rule. Real authentication would carry the
  identity as a verified claim and make the bound the provider's business.

---

## Deliberately not built

Named so that their absence reads as a decision rather than an omission.

- **Real authentication** (OIDC / Entra ID). Identity is two headers.
- **Postgres and Flyway.** H2 in-memory with `schema.sql` means the service
  runs with nothing but a JDK.
- **Testcontainers.** The same reasoning.
- **Event publishing to downstream consumers** via a transactional outbox.
- **A persistent idempotency store with a TTL.** Records live in the same
  in-memory database and vanish on restart, so a retry arriving after a restart
  creates a second request. Nothing expires or is swept.
- **Observability.** No metrics, no tracing. See the note on the `409` rate
  below for the one number that would be worth watching first.
- **JPA/Hibernate** (dirty checking works against immutability, and mapping
  buys nothing at three tables), **Lombok** (records suffice), **Spring
  Security** (identity is stubbed), and any JavaScript framework.

## What would change before production

In rough order of importance:

1. **Real authentication**, and with it the removal of every `X-User-Id` header
   read. The authorisation rules stay exactly as they are; only where identity
   comes from changes.
2. **Supersession modelled rather than inferred**, as described under
   Assumptions. "The most recent approval applies" is a rule living in a query
   instead of in the data.
3. **Postgres, with the application's database role holding no `UPDATE` or
   `DELETE` on `request_history`.** Append-only is currently a convention kept
   by code discipline; nothing in H2 enforces it. That guarantee belongs in the
   database.
4. **A persistent idempotency store with a retention period**, and something
   that sweeps it. The table currently grows without bound and is emptied only
   by restarting.
5. **Observability**, starting with the `409` rate.
6. **A validity period for approvals**, once the business answers whether one
   exists.

---

## Operational notes

**If the service misbehaves, check in this order:**

1. **Is it a `409` storm?** That usually means reviewers are working from a
   stale queue, not that the service is broken. See below.
2. **`request_history` should only ever grow.** If an entry has changed or
   disappeared, the audit guarantee is gone and that is the most serious thing
   that can happen here. No code path writes anything but an `INSERT`; that is
   the first thing to verify if it is ever in doubt.
3. **A request that is terminal but still changing** would mean the status
   guard has left the `UPDATE` statement. Same check: read the SQL, not the
   Java around it.
4. **Everything is in memory.** After a restart there is no data, and that is
   expected rather than a fault.

**What a rising `409` rate would mean.** A `409` is not an error in the usual
sense — it is the version guard doing its job. A low steady rate is healthy. A
rising one means decisions are being submitted against versions that have
already moved, which points at people, not code:

- Reviewers working from a queue they loaded some time ago.
- Relationship managers amending requests while they sit under review.
- Two reviewers looking at the same request.

The fix is a shorter path between reading a request and deciding it, not a
looser guard. If the rate is ever driven down by relaxing the version check,
the service has stopped answering the question it exists to answer.

There is nothing measuring this today — observability is out of scope — so it
would be the first metric to add.

---

## Repository layout

```
src/main/java/com/example/basis/
    ProblemDetailAdvice.java        every non-2xx answer, in one place
    request/
        ExceptionRequestController  HTTP; reads the identity headers
        ExceptionRequestStore       the SQL, and the transaction boundary
        ExceptionRequest            the request, with the rules that cannot race
        RequestStatus, EntryType, Decision, UserRole
src/main/resources/schema.sql       three tables, with the reasoning in comments
openspec/                           the specs, and the change history behind them
```

There is no service layer. Each store method is one transactional unit
corresponding to one transition, which is the granularity a service layer would
have had, and the domain rules live on the records as pure functions — which is
what lets them be unit-tested with no Spring context and no database.

## How the work is organised

Each slice is an OpenSpec change under `openspec/changes/` carrying its
proposal, spec delta, design and tasks. `openspec/specs/exception-request/spec.md`
is the current behaviour contract.

Tests and implementation are written in **separate sessions**, on purpose: a
test written alongside the code it tests encodes the same misreading twice.
Tasks name the acceptance criterion they satisfy so the test can be written from
the criterion rather than from the implementation.
