# Design

## Context

See proposal.md for motivation. Two constraints shape everything below.

The first is that `spring.sql.init.mode=embedded` is already set, and Spring
Boot's SQL initialisation runs a classpath-root `data.sql` automatically
wherever that mode applies — which includes every `@SpringBootTest` in the
suite, since the tests run the default profile against the same embedded H2.
The integration tests count rows and assert what a queue does *not* contain, so
a seed at the conventional path would not merely be untidy, it would make the
suite assert against a database it did not set up.

The second is that the seed is SQL written by hand against tables whose
consistency is normally guaranteed by the code that writes them. Nothing stops
a `data.sql` from inserting an `APPROVED` row with no `decided_at`, or a
version-2 request with one history entry. The invariants are only as good as
the fixture.

## Goals / Non-Goals

**Goals:**

- A dev-profile start puts a reviewer in front of a queue worth looking at, and
  a requester in front of their own requests, without anyone typing a `curl`.
- The suite behaves identically whether or not the seed exists.
- One scenario, two scripts, same steps, same narration, same order.

**Non-Goals:**

- A fixture that covers every state combination. Four requests cover the
  workflow's states. A fifth is there because it adds something none of them
  can: a request raised by a reviewer, which is what makes four-eyes visible in
  a queue rather than only in a test. A sixth that adds no screen is noise.
- Scripts that assert like a test suite. They narrate and they fail loudly when
  a step does not answer what it said it would, which is not the same as
  covering the behaviour — the integration tests do that.

## Decisions

### The seed lives outside the classpath root and is named by the dev profile

`src/main/resources/seed/data.sql`, loaded by
`spring.sql.init.data-locations=classpath:seed/data.sql` in a new
`application-dev.properties`. Run with `--spring.profiles.active=dev`.

The conventional `src/main/resources/data.sql` is rejected for the reason in
Context: Spring Boot would run it under every profile including the tests', and
the failure it caused would appear as unrelated assertion failures in tests
that never mentioned seed data. Putting the file where the convention does not
look, and naming it explicitly from the profile that wants it, means the
default profile cannot pick it up by accident.

*Alternative considered:* keeping `data.sql` at the root and guarding it with
`spring.sql.init.mode=never` in a test properties file. Rejected because it
inverts the safety: every future test class would depend on remembering the
guard, and a test that builds its own context would silently get the seed. The
current arrangement fails safe — the seed loads only where it is asked for.

### The fixture is SQL, and a test checks it

The rows are inserted directly rather than created through the API by a
`CommandLineRunner`. SQL states the fixture as a fact; a runner would replay
the workflow at every dev start, subject to the four-eyes rule, the version
guard and whatever order the runner's author chose, and a mistake there looks
like a broken service rather than a broken fixture.

The cost is that the fixture can contradict the invariants the service
enforces, so a test asserts what the seed claims: the amended request is at
version 2 with both a `CREATED` and an `AMENDED` entry, the decided ones carry
`decided_by` and `decided_at`, no request is decided by the person who raised
it, and every discount is within the permitted range. That test loads the
profile the seed belongs to and asserts against it, which is the only place in
the suite where the dev profile is active.

It must also run against a database of its own. `application.properties` pins
the URL to `jdbc:h2:mem:basis;DB_CLOSE_DELAY=-1`, and `DB_CLOSE_DELAY=-1` keeps
that database alive for the JVM, so every Spring context in the suite shares
it. A dev-profile context on that URL would load the seed into the database the
default-profile tests then read — the very contamination this design exists to
prevent — and the fixed identifiers would collide with themselves if the seed
loaded twice. The test overrides `spring.datasource.url` to a distinct in-memory
name, which keeps the seed out of the shared database and makes the dev-profile
context safe to create more than once.

A second test asserts the *absence* of the seed under the default profile. It
is short and it exists to fail if anyone moves the file to the classpath root —
the exact mistake this design is arranged to prevent.

### Identifiers and timestamps in the fixture are literal and fixed

UUIDs and `TIMESTAMP WITH TIME ZONE` values are written out rather than
generated. A fixed set of identifiers means a seeded request can be linked to
from a README or reached directly in the UI, and a rerun of the dev profile
produces the same database rather than one that differs by clock. The
timestamps are recent, ordered, and spaced by hours so that a history trail
reads as a sequence of events and the newest-first order is visible rather than
a tie.

They are written as UTC literals, which is what invariant 6 requires and what
the schema's `TIMESTAMP WITH TIME ZONE` columns store.

### The two scripts are one scenario written twice, and neither needs anything installed

Both run the same eight steps in the same order with the same narration:
create, replay the same `Idempotency-Key`, amend, approve the stale version
(`409`), approve the current version, attempt self-approval (`403`), read the
approved exception, and print what the request's history now holds.

`demo.sh` uses `curl` and POSIX text tools. It does **not** use `jq`: the point
of shipping both scripts is that neither has a prerequisite, and `jq` is absent
from a clean macOS and from Windows. The one value it must extract from a
response — the request id — is pulled with `sed`, which is enough for a field
the service itself formats.

`demo.ps1` uses `Invoke-RestMethod`, which is built in. Windows PowerShell 5.1
throws on a 4xx rather than returning it, and `-SkipHttpErrorCheck` exists only
on PowerShell 7, so the script wraps each call in a helper that catches the
exception and reads the status and the problem-detail body from it. That helper
is what makes the two `409`/`403` steps — the interesting ones — work on the
PowerShell that ships with Windows.

*Alternative considered:* calling `curl.exe` from the PowerShell script so that
one set of commands serves both. Rejected because `curl.exe` is present only on
Windows 10 1803 and later, and a PowerShell script that shells out to curl to
parse text is worse on the platform it exists to serve.

### A step that does not answer what it narrates stops the script

Each step states the status it expects, prints what it received, and exits
non-zero on a mismatch. A demo that narrates "this is rejected with 409" and
carries on after a `200` is worse than no demo: it tells a reviewer the
behaviour holds when it has just stopped holding. The scripts therefore read as
an executable version of the README's claims.

The application identifier is unique per run, so a second run of the script
against the same instance demonstrates the same thing rather than colliding
with the first run's approved exception. The `Idempotency-Key` is generated
once per run and reused by the replay step, which is the same rule the UI
follows.

## Risks / Trade-offs

**The fixture can drift from the invariants the code enforces.** A schema
change, or a new column that the writes populate, leaves the seed valid SQL and
wrong data. → The fixture test asserts the properties that matter rather than
the row count, so a column that stops being populated fails there. The check
constraint on `discount_bps` catches an out-of-range discount at startup, which
is a loud failure in the right place.

**The seed could be loaded where it must not be.** → Asserted from both sides:
one test requires it present under `dev`, on a database of its own, and one
requires it absent from the shared database under the default profile.

**The scripts can drift apart.** Two files describing one scenario is a
duplication that nothing mechanical checks. → Accepted, and mitigated by
writing them from the same step list and by the working agreement that a change
to one is a change to both. The alternative — one script generating the other,
or a single cross-platform runner — buys consistency with a build step or a
dependency this repository has decided not to take.

**A demo against a seeded instance adds requests to a database that already
has some.** → Intended. The scripts use their own application identifier and
their own users, and the queue growing is what a queue does.

## Migration Plan

Additive. The dev profile is opt-in: `./mvnw spring-boot:run` behaves exactly
as it does today, and `-Dspring-boot.run.profiles=dev` is what loads the seed.
Nothing existing is rewritten, so there is nothing to roll back beyond deleting
the new files.

## Open Questions

None. The one judgement worth recording — that seed data and scripts change no
requirement, so this change declares `skip_specs: true` — is stated in the
proposal rather than left open.
