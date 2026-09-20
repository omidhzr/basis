# Tasks

Groups 1–4 are implementation. Group 5 is verification and is written in a
**separate session**, from the scenarios in
`specs/exception-request/spec.md` and from acceptance criterion 6 in CLAUDE.md —
not from the code produced by groups 1–4.

This slice covers acceptance criterion 6. Criteria 1–5 are already met;
7–9 belong to the user interface, which is built after this.

## 1. History has one order

Done first because both of the later read paths depend on it, and because it
changes behaviour the existing suite already exercises.

- [x] 1.1 Order `historyOf` by `version DESC, occurred_at DESC, id DESC`, replacing `occurred_at DESC, version DESC`, with a comment saying why version leads. Verify by running `./mvnw test`: the existing assertions on history still pass, because they assert membership rather than order
- [x] 1.2 Verify the order holds for a request created, amended and decided — the decision first, then the amendment, then the creation — and that two entries sharing a version are separated by time

## 2. Reading one request

- [x] 2.1 Add the response shape carrying a request's current values together with its history entries, beside `RequestBodies.View` rather than by widening it, with each entry carrying its type, version, actor, time and payload. Verify the payload appears as a JSON object rather than an escaped string
- [x] 2.2 Add `GET /requests/{id}`, reading the identity headers as every other endpoint does and bounded the same way. Verify a created request reads back with its `CREATED` entry, and that an unknown identifier answers `404` with a problem detail

## 3. The queue

- [x] 3.1 Add the reviewer's query: requests **not** raised by the caller, `PENDING` absent a status, with the optional status applied as a single predicate. Verify a reviewer's own request never appears, whatever status is asked for — this is four-eyes enforced by the query rather than by the client
- [x] 3.2 Add the requester's query: requests raised by the caller in every state, narrowed by the optional status. Verify another user's request never appears
- [x] 3.3 Add `GET /requests`, choosing the query by `X-User-Role` and binding `status` to the `RequestStatus` enum so that an unknown value is the `400` the advice already answers. Verify each role receives its own queue from the same path, and that an invalid status is a problem detail

## 4. The approved exception

- [x] 4.1 Add the query resolving supersession — `status = 'APPROVED'` for the application, most recent `decided_at` first, one row — with a comment marking it as the inferred rule CLAUDE.md records as the weaker choice. Verify the most recent of two approvals is the one returned
- [x] 4.2 Add `GET /applications/{id}/approved-exception` returning the approved discount, the request it came from, and who decided it and when. Verify it answers `404` with a problem detail when the application has no approved request, including when it has only `PENDING`, `DECLINED` or `WITHDRAWN` ones — acceptance criterion 6

## 5. Verification — API integration tests

Separate session. `@SpringBootTest` with MockMvc against the real H2 schema,
one test per scenario in the spec delta.

- [ ] 5.1 Assert a request reads back with its current values and its history, and that an unknown identifier is `404` with a problem detail
- [ ] 5.2 Assert history is returned newest first for a request that was created, amended and decided, each entry naming the version it concerned
- [ ] 5.3 Assert a reviewer's queue holds `PENDING` requests raised by others and never their own, and that a requester's queue holds their own in every state and no one else's
- [ ] 5.4 Assert a status narrows the requester's queue, that a status other than `PENDING` widens the reviewer's to that status still excluding their own, and that an unknown status is `400`
- [ ] 5.5 Assert the approved exception is returned for an application, that the most recent of two approvals wins, and that an application with none is `404` — acceptance criterion 6
- [ ] 5.6 Correct the comments in `ExceptionRequestApiTest` and `IdempotentCreateTest` that explain history is read through the store "because the read endpoints belong to a later slice". Verify the assertions themselves are unchanged — what they assert is still worth asserting directly
- [ ] 5.7 Run `./mvnw test` and confirm the whole suite is green before the change is verified or archived
