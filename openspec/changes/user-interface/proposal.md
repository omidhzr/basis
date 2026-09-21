# Proposal

## Why

The service is complete as an API and can be started with demonstration data,
but the only way to use it is `curl` or a script. The three acceptance criteria
that remain unmet — 7, 8 and 9 in `CLAUDE.md` — are all about a page: that it is
served at `/` with no build step and no network, that the decision panel shows
the version it is deciding and explains a stale-version rejection, and that
creating, amending and deciding can each be done from the UI alone.

This is also the slice that makes the contested requirement visible to someone
who is not reading code. Versioned requests with version-bound decisions are
easy to state and hard to feel; a reviewer who watches "recorded against
version 2" turn into an explanation of what changed has understood the design.
It is built last, as the working agreement requires, because the API and its
tests are green and the seed gives it something to show.

## What Changes

- **One static page**, `src/main/resources/static/index.html`, served by the
  service at `/`. Plain JavaScript with `fetch` and the DOM API; Pico.css
  vendored under `static/vendor/`. No controller, no build step, no framework,
  nothing loaded from a CDN.
- **Three sections toggled by state**: the queue (whatever `GET /requests`
  returns for the caller), the detail view (current values, full history newest
  first with the version each entry concerns, and the actions the caller's role
  offers), and the create form.
- **An identity switcher** in the header that sets `X-User-Id` and
  `X-User-Role` on every call. Switching identity is how the workflow is
  demonstrated, and it changes what the queue contains rather than which
  buttons are hidden.
- **The visible form of the invariants**: the decision panel names the version
  being decided; a `409` is rendered as an explanation with the current version
  and a way to reload; basis points are shown beside percentage points; the
  amend control sends the version displayed; the create form's
  `Idempotency-Key` is generated once per opening and reused on retry; every
  error shown is read from the `ProblemDetail` body.
- **A README update** — status table, how to open the page, and a plain
  statement that criteria 7–9 are verified manually and not by the suite.

**Deliberately not in this change:**

- **Any rule the API does not also enforce.** The page holds no business logic
  and does no client-side validation — no `min`, `max` or `minlength`
  attributes — so the bounds stay in one place and a bad value is rejected with
  the API's own words.
- **Use of `GET /applications/{id}/approved-exception`.** The three screens in
  `CLAUDE.md` do not include it; it is what the mortgage process consumes, and
  the demo scripts already show it.
- **A status filter on the queue.** The API accepts one; the screen contract
  does not ask for it.
- **Automated UI tests.** `CLAUDE.md` puts criteria 7–9 under manual
  verification and asks that this be said rather than implied. A browser test
  stack would be a dependency the project has decided not to take.
- **Authentication, routing, or a state library.** Identity stays a stub;
  the page is one document.

## Capabilities

### New Capabilities

- `request-console`: what the page shows and does for a caller — the queue
  and detail views, the identity switcher, the decision, amend, withdraw and
  create actions, and how the invariants and API errors are made visible. It is
  a client of `exception-request` and states no server behaviour of its own.

### Modified Capabilities

None. Nothing the service must do changes: the page consumes endpoints that
`exception-request` already specifies, and no server code is touched.

## Impact

**Code and resources.** New: `src/main/resources/static/index.html` and
`src/main/resources/static/vendor/` holding Pico.css and its licence. Changed:
`README.md`. No Java, no SQL and no configuration changes — Spring Boot serves
`static/` and its `index.html` welcome page by default.

**Dependency.** Pico.css is the one front-end asset, named in `CLAUDE.md`'s
technology table. It is committed to the repository rather than fetched at
runtime, so the page renders on a machine with no network access (criterion 7);
obtaining the file is a one-off act during implementation, not a runtime or
build dependency.

**Tests.** None added. The existing suite must stay green and is unaffected,
because static resources are outside every test's path. Criteria 7–9 are
verified by hand against the seeded service and recorded as such.

**Exposure.** The page renders text that users typed — a request's reason, a
reviewer's note, an identity — so it must never be inserted as markup. This is
a property of the implementation, stated in design, and it is the one place a
UI in this domain can introduce a vulnerability the API does not have.
