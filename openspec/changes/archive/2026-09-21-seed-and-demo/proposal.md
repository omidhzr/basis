# Proposal

## Why

The service is complete as an API and empty on every start. A reviewer who runs
it has to create a request, amend it, switch identity and decide it before there
is anything to look at, and the only way to see that the interesting
behaviours — an idempotent replay, a stale-version `409`, a refused
self-approval — actually happen is to type them out from the README. Both
deliverables that close that gap are named in `CLAUDE.md` and neither exists:
seed data loaded at startup under a dev profile, and a pair of demo scripts
that narrate the scenario end to end.

This is also the slice the user interface needs underneath it. A page opened on
an empty database demonstrates nothing; opened on a seeded one, the queue has
contents, the identity switcher changes what a caller sees, and a request that
is already at version 2 is there to be decided.

## What Changes

- **Seed data at startup, under a dev profile only.** A handful of requests in
  the states a reviewer needs to see: one `PENDING` at version 1, one amended to
  version 2 and still `PENDING`, one `APPROVED`, one `DECLINED`. Each carries
  the history entries its life implies, so the trail a detail view shows is a
  real trail rather than a single row. Raised by more than one relationship
  manager, so the reviewer queue and the requester queue differ visibly. A
  fifth `PENDING` request is raised by a reviewer: nothing restricts raising a
  request to a relationship manager, and four-eyes is only visible in a queue if
  a reviewer has one to be kept out of. Their queue leaves it out and another
  reviewer's holds it.
- **The seed is invisible to the test suite.** `spring.sql.init.mode=embedded`
  makes Spring Boot run a classpath-root `data.sql` automatically, including in
  `@SpringBootTest`, where it would break assertions that count rows and check
  what a queue does not contain. The seed is therefore loaded by an explicitly
  configured location under the dev profile, not by the filename Spring Boot
  picks up on its own.
- **`scripts/demo.sh` and `scripts/demo.ps1`**, the same scenario in both, so
  it runs without a shell prerequisite on macOS, Linux or Windows: create,
  retry with the same idempotency key, amend, attempt to approve the stale
  version (`409`), approve the current version, attempt a self-approval
  (`403`), then read the approved discount. Each step is narrated with what was
  expected and what came back.
- **A note in the README** on running with the dev profile and on what each
  script demonstrates, replacing the "no seed data yet" line the README carries
  today.

**Deliberately not in this change:**

- **Seeding through the API instead of SQL.** A `CommandLineRunner` that posts
  requests would keep the invariants enforced by the code that owns them, but
  it would also mean the dev profile runs a workflow at startup, including the
  four-eyes and version rules, in the order the runner happens to write them.
  SQL states the fixture directly, and the fixture is checked by a test rather
  than by hoping the runner stays correct.
- **A seeded idempotency record.** The replay contract is what the demo script
  exercises live. Seeding a used key would leave a key nobody can replay
  without knowing it.
- **Making the scripts a test.** They need a running service on a port; the
  suite must stay `./mvnw test` with no prerequisite. What the scripts assert
  in prose, the integration tests already assert in JUnit.

## Capabilities

### New Capabilities

None. Nothing the service must do changes.

### Modified Capabilities

None. Seed data is a development fixture and the scripts are a narration of
endpoints that already exist and are already specified — the request they
create, the replay they retry and the `409` they provoke are covered by
requirements in `exception-request` today. No requirement is added, and none is
revised, so this change sets `skip_specs: true` rather than inventing one.

## Impact

**Code and resources.** New: `src/main/resources/seed/data.sql` and the dev
profile that points at it, `scripts/demo.sh`, `scripts/demo.ps1`. Changed:
`application.properties` gains nothing it does not already need; the dev
profile lives in its own `application-dev.properties` so that the default
profile — the one the tests run under — is untouched.

**Tests.** One test asserting the seed is absent under the default profile, so
that a later move of the file to the classpath root cannot quietly poison the
suite, and one asserting, on an in-memory database of its own, that the seeded
fixture satisfies the invariants it claims — a version-2 request having both a
`CREATED` and an `AMENDED` entry, a decided one having `decided_by` and
`decided_at`.

**No new dependency.** The seed is SQL against the existing schema. `demo.sh`
uses `curl` and POSIX text tools, not `jq`, which is not present by default on
Windows or on a clean macOS; `demo.ps1` uses `Invoke-RestMethod`, which is
built in.

**`.gitattributes` already covers the scripts**: `* text=auto eol=lf` with
`*.ps1 text eol=crlf`, so `demo.sh` is committed LF and `demo.ps1` CRLF without
a change to that file. The executable bit on `demo.sh` still has to be set.
