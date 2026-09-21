# Tasks

Groups 1–5 build the page, group 6 the README. Group 7 is verification and is
walked from the scenarios in `specs/request-console/spec.md` and from criteria
7–9 in `CLAUDE.md`, not from the code in `index.html` — by hand, because
`CLAUDE.md` puts those criteria under manual verification. The README says so.

Everything is checked against the seeded service:
`./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`, then
`http://localhost:8080/`. No task changes Java, SQL or configuration; if one
seems to, stop and raise it.

## 1. The page and its stylesheet

Done first because every later task needs somewhere to live and something to be
seen through.

- [x] 1.1 Fetch Pico.css once, at a pinned 2.x release, and commit `static/vendor/pico.classless.min.css` with its MIT licence beside it, and note the version and source URL for the README task. Verify `GET /vendor/pico.classless.min.css` answers `200` from the running service, and that `./mvnw test` is still green with 72 tests
- [x] 1.2 Write the skeleton of `static/index.html`: header, three `<section hidden>` elements (queue, detail, create), the `el` helper that builds elements with no HTML path, the `call` function that adds the identity headers and returns status plus parsed body, and the one function that renders a problem detail. No class names, no external URL. Verify `/` answers `200` styled by Pico, and that a text search of the file finds no `http://`, `https://`, `class=` or `innerHTML`

## 2. Identity and the queue

- [x] 2.1 Add the identity switcher: user-id field with a `<datalist>` of `rm-1`, `rm-2`, `reviewer-1`, `reviewer-2`, a role select of `RELATIONSHIP_MANAGER` and `REVIEWER`, held in `sessionStorage` behind try/catch, defaulting to `rm-1` as `RELATIONSHIP_MANAGER`, re-reading the queue on change. Verify in the browser's network panel that every call carries `X-User-Id` and `X-User-Role` matching the switcher, and that the page still works with storage blocked
- [x] 2.2 Render the queue from `GET /requests`: application, discount as `N bp` beside `N/100 percentage points` (integer arithmetic), reason, requester, status, submitted time as `YYYY-MM-DD HH:MM UTC` from the returned string, version; each row opens the detail. Verify as `reviewer-1` the queue is `APP-2003` and `APP-2004` and not `APP-2005`, as `reviewer-2` it also holds `APP-2005`, as `rm-2` it is `APP-2002` and `APP-2003`, and `25` shows as `25 bp` beside `0.25 percentage points`

## 3. The detail view

- [x] 3.1 Render the detail from `GET /requests/{id}`: current values, then the history newest first with version, entry type, actor, UTC time and the payload's discount, reason or note. Hold the id in the URL fragment so a request can be opened directly, and return to the queue from the back button. Verify `APP-2003` lists its `AMENDED` entry at version 2 before its `CREATED` entry at version 1, that `/#5eed0000-0000-4000-8000-000000000005` opens as `reviewer-1` although it is not in that queue, and that an unknown identifier shows the service's `404` title and detail
- [ ] 3.2 Decide which actions are offered from the role, the status and `requestedBy` compared with the identity, in one function: decision panel to a `REVIEWER` on a `PENDING` request they did not raise; amend and withdraw to a `RELATIONSHIP_MANAGER` on a `PENDING` request they did raise; nothing on a terminal request. Verify `reviewer-1` sees the panel on `APP-2004` and no approve or decline control on `APP-2005`; `rm-1` sees amend and withdraw on `APP-2004` and neither on `APP-2003`; `APP-2001` and `APP-2002` offer nothing to anyone

## 4. The actions

- [x] 4.1 Add the decision panel: states "this decision is recorded against version N" using the version shown, an optional note, and `APPROVE` and `DECLINE` buttons that submit that version, then re-read the request. Verify as `reviewer-1` on `APP-2003` the panel says version 2 and the network panel shows the call carrying `version` 2, and that the note appears in the resulting history entry. Satisfies the first half of criterion 8
- [ ] 4.2 Add `explainConflict` for a `409` from a decision, amendment or withdrawal: that the request changed while open, `currentVersion` and `currentStatus` from the response, the service's `detail` verbatim, and a button that re-reads the request; shown without a version when the response carries none. Verify with two tabs — `reviewer-1` on `APP-2004` at version 1, then `rm-1` in another tab amends it — that the reviewer's decision shows the explanation naming version 2, records nothing, and that the reload button shows version 2 with the panel now naming it. Satisfies the second half of criterion 8
- [ ] 4.3 Add amend: a form prefilled with the displayed values that sends the displayed version and only the fields whose value changed. Verify as `rm-1` on `APP-2004` that changing the discount sends `version` 1 with only `discountBps`, the request then shows version 2 and a new history entry, and that submitting with nothing changed shows the service's `400`
- [ ] 4.4 Add withdraw: a button that submits the displayed version then re-reads the request. Verify as `rm-1` on a freshly created request that it becomes `WITHDRAWN` and then offers nothing
- [ ] 4.5 Route every non-2xx result through the one problem renderer, and show a network failure as the browser's own message. Verify a discount of `500` shows the service's `400` title and detail, that `reviewer-1` deciding `APP-2005` by a forced call (browser console `fetch` with the same headers) shows the service's `403` explanation, and that stopping the service and clicking shows the browser's error rather than invented text

## 5. Creating a request

- [ ] 5.1 Add the create form: application identifier, discount in basis points, reason; a `novalidate` form with no `min`, `max`, `minlength` or `required`; an empty number omitted from the body. Generate the `Idempotency-Key` from `crypto.getRandomValues` as a version-4 UUID when the form is opened, reuse it on every submission until a create succeeds or the form is closed, disable the button while a call is in flight, and on `201` open the new request. Verify a valid submission shows the new request `PENDING` at version 1; that submitting a bad value, correcting it and submitting again sends the same key both times; that closing and reopening the form sends a different key; and that the page works when opened by the machine's network address rather than `localhost`

## 6. The README

- [ ] 6.1 Update the README: the status table row for the user interface, how to open the page, the pinned Pico.css version and source, the two readings made where `CLAUDE.md` pulls against itself (offering by ownership, and the reviewer who raised a request being unable to amend or withdraw it from the page), and a plain statement that criteria 7–9 are verified by hand. Verify every command in the new text runs as written

## 7. Verification — the page by hand

Walked from the spec's scenarios and criteria 7–9, not from `index.html`, and
ideally by someone other than whoever wrote the page. Each check is recorded as
done only when it has been seen to happen.

- [ ] 7.1 Criterion 7: from a clean clone with no network, run `./mvnw spring-boot:run` (no profile) and open `/`; the page renders fully styled, and the network panel shows requests to the service only
- [ ] 7.2 Criterion 8: the decision panel names the version it is deciding, and a stale-version rejection is shown as an explanation of what changed rather than an error code — the two-tab scenario in 4.2, repeated from a fresh start
- [ ] 7.3 Criterion 9: from a fresh start, as `rm-1` create a request, amend it, switch identity to `reviewer-2`, open it from the queue and approve it, without any client but the page; the history shows `CREATED`, `AMENDED` and `APPROVED`, each with its version. Then create and withdraw a second request
- [ ] 7.4 Required behaviour 8: with the network panel open, submit the create form twice in a row and confirm one request exists and both calls carried the same `Idempotency-Key`
- [ ] 7.5 Markup safety: create a request whose reason is `<img src=x onerror=alert(1)> retention case`, and confirm it displays as literal characters in the queue and the detail and that nothing executes
- [ ] 7.6 Run `./mvnw test` and confirm the suite is green and still 72 tests: the page changes no server code, and the suite is unaffected by its existence
