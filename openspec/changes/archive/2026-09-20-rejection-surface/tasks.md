# Tasks

Groups 1–3 are implementation. Group 4 is verification and is written in a
**separate session**, from the scenarios in
`specs/exception-request/spec.md` — not from the code produced by groups 1–3.

No acceptance criterion in CLAUDE.md covers this change: it closes the gap
between what the `Report errors as problem details` requirement already
promises and what the service does, and adds two rejections the brief leaves
undefined and the business has now settled.

## 1. Every rejection is a problem detail

- [x] 1.1 Make the one `@RestControllerAdvice` extend `ResponseEntityExceptionHandler`, converting the existing `MethodArgumentNotValidException` and `HandlerMethodValidationException` handlers into `@Override`s of the base class's methods. Verify the service starts — two `@ExceptionHandler` methods for one exception type in one bean is an ambiguous mapping and fails at startup, so starting is the check
- [x] 1.2 Verify the four `400` details `request-lifecycle` asserts are unchanged, each still naming the field or header that failed, by running `./mvnw test` before going further
- [x] 1.3 Verify an unsupported content type, an unsupported method and an unknown path each answer `application/problem+json` with a title and a detail

## 2. Identity is bounded

- [x] 2.1 Add `@Size(max = RequestLimits.MAX_IDENTITY_LENGTH)` to the `X-User-Id` header parameter on all four endpoints, using the constant defined but unused since `request-lifecycle`. Verify an identity of 128 characters is accepted and 129 is rejected with `400` rather than reaching the database
- [x] 2.2 Verify a rejected call stores nothing: no request row, no history entry, no idempotency record

## 3. An amendment carries something to amend

- [x] 3.1 Reject a `PATCH` carrying neither `discountBps` nor `reason` with `400`, from where the amend body arrives, raising an exception the advice answers with a written sentence rather than a field name. Verify the request is left at its current version with no history entry appended
- [x] 3.2 Verify an amendment carrying either field alone still succeeds, which is the behaviour `request-lifecycle` established and this change must not narrow

## 4. Verification — API integration tests

Separate session. `@SpringBootTest` with MockMvc against the real H2 schema,
one test per scenario added to the spec delta.

- [x] 4.1 Assert an unsupported content type, an unsupported method and an unknown path each return a problem detail with a title and a detail. Verify each asserts the content type, not only the status — the status was already right before this change and the body was not
- [x] 4.2 Assert an `X-User-Id` of 129 characters is `400` on create, amend, decide and withdraw, and that 128 is accepted
- [x] 4.3 Assert a `PATCH` carrying only the version is `400`, and that the request is unchanged with no history entry appended
- [x] 4.4 Assert an amendment carrying only the discount, and one carrying only the reason, each still succeed
- [x] 4.5 Run `./mvnw test` and confirm the whole suite is green before the change is verified or archived
