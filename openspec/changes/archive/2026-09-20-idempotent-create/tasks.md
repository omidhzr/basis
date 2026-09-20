# Tasks

Groups 1–3 are implementation. Group 4 is verification and is written in a
**separate session**, from the scenarios in
`specs/exception-request/spec.md` and from acceptance criterion 2 in
CLAUDE.md — not from the code produced by groups 1–3. Tests and implementation
written in one pass would encode the same misreading twice.

This slice covers acceptance criterion 2. Criteria 1 and 3–5 are already met by
`request-lifecycle`; 6 belongs to the queries slice and 7–9 to the user
interface.

## 1. Storage

- [x] 1.1 Add `idempotency_record` to `schema.sql` with the columns CLAUDE.md names — `key`, `caller`, `request_id`, `response_body`, `status_code`, `created_at` — primary key `(key, caller)`, quoting `key` as a reserved word in H2 2.x. Verify the service starts against the schema and that an unquoted reference fails, which is what makes the quoting deliberate rather than decorative
- [x] 1.2 Add the foreign key from `request_id` to `exception_request`, matching `request_history`. Verify an insert naming an unknown request is refused by the database

## 2. The create path

Each task stays inside the one transactional method that already writes the
request row and its `CREATED` entry.

- [x] 2.1 Read `Idempotency-Key` on `POST /requests` beside the identity headers, bounded non-blank and at most 128 characters. Verify a missing, blank or over-length key is rejected with `400` and creates nothing
- [x] 2.2 Insert the idempotency record as the last statement of the create transaction, built from the response value the endpoint returns, with `caller` taken from `X-User-Id`. Verify a successful create stores exactly one record carrying the returned status and body
- [x] 2.3 Catch the unique-constraint violation on that insert rather than checking for the key first. Verify no code path reads `idempotency_record` before writing it — this is invariant 7, and a check-then-insert passes every single-threaded test in group 4
- [x] 2.4 Replay a caught violation from a new transaction, after the failed one has rolled back: read the stored record and return its status and body unchanged, including the `Location` header. Verify a retry returns the original `201` and creates no second request or history entry, satisfying acceptance criterion 2
- [x] 2.5 Compare the retried body against the create fields carried in the stored `response_body`, not against the current `exception_request` row, and answer `422` when they differ. Verify a request created, amended, and then retried with its original body still replays rather than conflicting — the case that distinguishes the two sources

## 3. HTTP surface

- [x] 3.1 Add the `422` to the one `@RestControllerAdvice`, with a detail naming the key and saying it was already used for a different request. Verify the response is a problem detail with a title and an actionable detail
- [x] 3.2 Document the header on the create operation so it appears in the generated OpenAPI. Verify it is listed as required at `/swagger-ui.html`

## 4. Verification — API integration tests

Separate session. `@SpringBootTest` with MockMvc against the real H2 schema,
one test per scenario in the spec delta.

- [x] 4.1 Replay a create with the same key and body; assert the original status, body and `Location` are returned and that exactly one request and one `CREATED` entry exist — acceptance criterion 2
- [x] 4.2 Assert a create with a missing, blank or over-length key is `400` and stores nothing
- [x] 4.3 Reuse a key with a different application identifier, a different discount and a different reason in turn; assert `422` for each and that the original request is unchanged. Verify this fails if the comparison drops a field
- [x] 4.4 Assert the same key from a different `X-User-Id` creates a request of its own rather than replaying the first caller's
- [x] 4.5 Submit two creates carrying the same key from the same caller concurrently, released from a barrier as `ConcurrentAmendmentTest` does; assert exactly one request and one `CREATED` entry exist and that neither caller receives a `5xx`. Verify this fails if the guard is moved out of the constraint
- [x] 4.6 Assert a create rejected with `400` or `422` appends no history entry and stores no idempotency record
- [x] 4.7 Run `./mvnw test` and confirm the whole suite, including the tests `request-lifecycle` left behind, is green before the change is verified or archived
