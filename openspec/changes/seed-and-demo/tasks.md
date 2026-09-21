# Tasks

Groups 1–2 are the seed, group 3 the scripts, group 4 the README. Group 5 is
verification and is written in a **separate session**, from this change's
proposal and design and from the deliverables in CLAUDE.md — not from the files
groups 1–3 produce.

No acceptance criterion in CLAUDE.md is met by this change: 1–6 are already
met, and 7–9 belong to the user interface. What this change delivers is the
seed data and the demo scripts named under Deliverables.

## 1. The dev profile

Done first because it decides where the seed file may live, and the seed is
worth nothing until something loads it.

- [x] 1.1 Add `application-dev.properties` naming the seed location explicitly, leaving `application.properties` alone. Verify `./mvnw test` is still green — the default profile must load nothing new
- [x] 1.2 Confirm the dev profile is reachable from the documented command (`./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`) and that the service starts on an empty seed file, so that a failure in group 2 is a failure of the data rather than of the wiring

## 2. The seed

- [x] 2.1 Write `src/main/resources/seed/data.sql` inserting five requests: four raised by two relationship managers (one `PENDING` at version 1, one `PENDING` at version 2 after an amendment, one `APPROVED`, one `DECLINED`) and one `PENDING` raised by a reviewer, so four-eyes is visible in a queue. Fixed UUIDs, fixed UTC timestamps, hours apart. Verify each row satisfies the check constraint and the service starts under the dev profile
- [x] 2.2 Append the history each request's life implies — `CREATED` for every request, `AMENDED` for the version-2 one, `APPROVED` and `DECLINED` for the decided ones, each naming the version it concerned and an actor who is not the requester for a decision. Verify `GET /requests/{id}` on the amended request returns its two entries newest first, the v2 amendment before the v1 creation, and on a decided one returns its decision
- [x] 2.3 Verify by hand that the two queues differ: a reviewer's `GET /requests` holds the pending requests raised by others and not their own, and each seeded requester's queue holds only their own. In particular the reviewer-raised request is absent from that reviewer's queue and present in another reviewer's. This is what makes the identity switcher worth having in the UI slice

## 3. The demo scripts

- [x] 3.1 Write `scripts/demo.sh`: the eight steps in design.md, each narrating the status it expects, printing what came back, and exiting non-zero on a mismatch. `curl` and POSIX tools only, no `jq`. A unique application identifier and one `Idempotency-Key` per run. Verify by running it against a service started with no profile and again with `dev`
- [x] 3.2 Commit `demo.sh` with the executable bit set. Verify `git ls-files -s scripts/demo.sh` reports mode `100755`, and that `.gitattributes` already gives it LF without a change to that file
- [x] 3.3 Write `scripts/demo.ps1`: the same eight steps, the same narration, the same order, using `Invoke-RestMethod` with the helper that reads a 4xx's status and problem detail on Windows PowerShell 5.1. Verify by running it on 5.1 and confirming the `409` and `403` steps report their status rather than throwing
- [x] 3.4 Read the two scripts side by side and confirm they narrate the same scenario in the same words. Verify the step count, the order and the expected statuses match line for line

## 4. The README

- [x] 4.1 Replace the "no seed data yet" line with how to start under the dev profile and what the seed contains, and add what each script demonstrates and how to run it on either platform. Verify the commands in the README run as written on a clean checkout

## 5. Verification — the seed under test

Separate session. `@SpringBootTest`, written from the design's two assertions
about the seed and from the invariants in CLAUDE.md.

- [ ] 5.1 Assert that under the default profile none of the seed's fixed identifiers is present in the shared database — other tests' requests legitimately are — so that moving the file to the classpath root fails here rather than in unrelated tests
- [ ] 5.2 Assert, under the dev profile and against a distinct in-memory database URL so the seed never reaches the database the rest of the suite shares, that the fixture satisfies what it claims: the amended request at version 2 carrying both a `CREATED` and an `AMENDED` entry, each decided request carrying `decided_by` and `decided_at`, no request decided by the person who raised it, and every discount within the permitted range
- [ ] 5.3 Run `./mvnw test` and confirm the whole suite is green, including that the default-profile tests are unaffected by the existence of the seed
