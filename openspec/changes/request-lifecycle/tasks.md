# Tasks

Groups 1–4 are implementation. Groups 5 and 6 are verification and are written
in a **separate session**, from the scenarios in
`specs/exception-request/spec.md` and the numbered acceptance criteria in
CLAUDE.md — not from the code produced by groups 1–4. Tests and implementation
written in one pass would encode the same misreading twice.

Acceptance criteria 2 and 6 belong to later slices (idempotency, queries), and
7–9 to the user interface. This slice covers 1, 3, 4 and 5.

## 1. Project skeleton

- [x] 1.1 Create the Maven project: `pom.xml` and the Maven wrapper, Java 17, Spring Boot 3 with web, jdbc and validation, H2 and springdoc-openapi. Verify `./mvnw -q package` succeeds on a clean checkout with only a JDK present
- [x] 1.2 Add the application entry point under `com.example.basis`. Verify the service starts and `/swagger-ui.html` responds
- [x] 1.3 Add `schema.sql` creating `exception_request` and `request_history` with the columns named in CLAUDE.md's data model. Verify the service starts against the schema and both tables accept an insert. No `idempotency_record` — that table belongs to the idempotency slice

## 2. Domain types and rules

Pure Java, no Spring context, no database. These are what group 5 tests.

- [ ] 2.1 Add the `status` and `entry_type` enumerations using exactly the values in CLAUDE.md's enumeration table. Verify no synonym, abbreviation or lower-case variant appears in the source
- [ ] 2.2 Add the request record carrying current state, and a function answering which transitions are legal from a given status. Verify every transition in CLAUDE.md's state machine is accepted and every other is refused
- [ ] 2.3 Add the four-eyes rule, the reviewer role check and the ownership checks for amend and withdrawal as pure functions returning the reason for refusal. Verify each returns a refusal a caller could act on
- [ ] 2.4 Add the discount bound 1–200 as a named constant. Verify it is referenced by validation rather than repeated as a literal

## 3. Persistence

Each task is one transactional unit: the guarded write and its history entry
commit together or not at all.

- [ ] 3.1 Insert a new request as `PENDING` at version 1 and append its `CREATED` history entry in one transaction. Verify a created request and its entry are both present, satisfying acceptance criterion 1
- [ ] 3.2 Amend with the version and status guards inside the `UPDATE` statement, appending `AMENDED` and incrementing version. Verify `rowsAffected == 0` when the submitted version is not current, satisfying acceptance criterion 3
- [ ] 3.3 Decide with the same guarded form, appending `APPROVED` or `DECLINED`, recording the deciding user, the decision time and any note. Verify the outcome records who and when, satisfying acceptance criterion 4
- [ ] 3.4 Withdraw with the same guarded form, appending `WITHDRAWN`. Verify a withdrawn request is terminal afterwards
- [ ] 3.5 Classify a failed guarded write by re-reading the row: absent is `404`, otherwise `409` carrying the current version. Verify the re-read influences only the message, never whether the write proceeds

## 4. HTTP surface

- [ ] 4.1 Add request and response records with Bean Validation annotations taken from CLAUDE.md's validation table. Verify each bound rejects a value outside it with `400`
- [ ] 4.2 `POST /requests`. Verify `201` with a `Location` header naming the new request, satisfying acceptance criterion 1
- [ ] 4.3 `PATCH /requests/{id}`. Verify `200` on the current version, `409` on a stale one, `403` for a caller who did not raise it, satisfying acceptance criterion 3
- [ ] 4.4 `POST /requests/{id}/decision`. Verify `200` for a reviewer on the current version, `409` on a stale one, `403` without the reviewer role, satisfying acceptance criterion 4
- [ ] 4.5 `POST /requests/{id}/withdrawal`. Verify `200` for the requester and `403` for anyone else
- [ ] 4.6 Add one `@RestControllerAdvice` producing RFC 7807 problem details for `400`, `403`, `404` and `409`. Verify a stale-version rejection states the current version in its detail
- [ ] 4.7 Read identity from the `X-User-Id` and `X-User-Role` headers at each call site, with a comment recording that these stand in for JWT claims. Verify no authentication machinery is introduced

## 5. Verification — domain unit tests

Separate session. Written from the spec scenarios, in plain JUnit with no
Spring context.

- [ ] 5.1 Test the state machine against CLAUDE.md's diagram: every legal transition accepted, every illegal one refused. Verify the suite runs without a Spring context
- [ ] 5.2 Test that a decision or amendment against a non-current version is refused, and that the refusal names the current version
- [ ] 5.3 Test four-eyes, the reviewer role check and amend and withdrawal ownership, each for both the permitted and the refused caller
- [ ] 5.4 Test the validation bounds at both edges: discount 1 and 200 accepted, 0 and 201 refused; reason at 10 and 500 accepted, 9 and 501 refused

## 6. Verification — API integration tests

Separate session. `@SpringBootTest` with MockMvc against the real H2 schema,
one test per scenario in the spec delta.

- [ ] 6.1 Create a request and assert `PENDING`, version 1 and a `CREATED` entry — acceptance criterion 1
- [ ] 6.2 Amend, then approve the previous version and assert `409` — acceptance criterion 3
- [ ] 6.3 Approve the current version as a reviewer and assert the outcome records who and when — acceptance criterion 4
- [ ] 6.4 Attempt to approve one's own request and assert `403` — acceptance criterion 5
- [ ] 6.5 Attempt to amend and to decide a terminal request and assert `409` for both
- [ ] 6.6 Submit two amendments against the same version concurrently and assert exactly one succeeds and the other receives `409`. Verify this fails if the guard is moved out of the `UPDATE` statement
- [ ] 6.7 Assert that calls rejected with `400`, `403` and `409` append no history entry
- [ ] 6.8 Assert every non-2xx response carries a problem detail with a title and an actionable detail
- [ ] 6.9 Run `./mvnw test` and confirm the whole suite is green before the change is verified or archived
