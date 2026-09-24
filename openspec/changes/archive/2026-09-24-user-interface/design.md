# Design

## Context

See proposal.md for why, and `CLAUDE.md` under "User interface" for the screens,
the eight required behaviours and the ground rules (one page, plain JavaScript,
vendored Pico.css, no rule the API does not also enforce). None of that is
restated here. This records only what `CLAUDE.md` leaves open.

Two facts about the API shape the page. A write returns the request's *current
values* and no history, so a page that wants the trail reads the request again.
And a `409` carries `currentVersion` and `currentStatus` beside its `title` and
`detail`, so the explanation the page owes a reviewer can be built from the
response rather than inferred.

Two lines of `CLAUDE.md` pull against each other, and the resolution below is a
reading, not a certainty. "The caller's role decides which actions are offered,
and the server decides which are permitted" suggests offering by role and
letting the server refuse; "if a request is reached directly, no approve control
is offered" needs the page to know whose request it is. See the decision on
offering actions.

## Goals / Non-Goals

**Goals:**

- A reviewer who opens the page on a seeded service can watch the
  version-binding happen: a decision naming version 2, a stale one explained.
- The page cannot be made to disagree with the API, because it holds no rule the
  API lacks.
- One file a reader can hold in their head, and no request to anything but the
  service.

**Non-Goals:**

- Polish beyond what classless Pico gives: no responsive design work, no
  internationalisation, no accessibility audit beyond using semantic HTML.
- Live updating. See the risk on stale views.

## Decisions

### One file: markup, style and script inline

`index.html` carries its own `<script>` and any small `<style>`; only Pico.css is
a separate file, under `static/vendor/`. `CLAUDE.md` calls it a single static
page, and one file means one thing to read and no load order to get wrong.
*Alternative considered:* `app.js` beside it. Rejected: it is a second file to
open for no gain at this size, and can be split the day the page stops fitting on
a few screens.

### Text is inserted as text, never as markup

Every value that originated with a user — reason, note, application identifier,
identity — is put into the page with `textContent` or as a text node, through a
small `el(tag, attrs, ...children)` helper that builds elements and has no HTML
path. *Alternative considered:* `innerHTML` with an escaping function. Rejected:
it is safe only until one call site forgets, and in a page where a reason is
free text written by one person and read by another with authority to approve, a
stored script is the one flaw this layer can add that the API does not have.

### Section toggling by state, with the request id in the fragment

Three `<section>` elements, one shown at a time via the `hidden` attribute; the
page's state is a few module-level variables (identity, the request on show, the
create form's key). The fragment holds the request id while a detail is open, so
a request can be reached directly — which `CLAUDE.md` and the seed both assume
("reached directly in the UI") — and the back button returns to the queue. No
router and no library: one `hashchange` handler. *Alternative considered:*
State only, no fragment. Rejected because a request could then not be linked to,
and the four-eyes behaviour that depends on reaching one directly would be
untestable by hand.

### One call function, one place that reads a problem

All traffic goes through `call(method, path, body, extraHeaders)`, which adds the
identity headers and returns the status with the parsed body; a non-2xx body is
read as the problem detail whatever its content type says. Everything that
renders a refusal goes through one function fed by that result. That is what
makes "the service's own words" a property of the page rather than a discipline
at each call site. A call that fails before any response exists has no problem
detail; the page shows the error the browser reports, unedited, since there is
nothing of the service's to show and inventing wording is what requirement 6
forbids.

### Offering actions: by role and ownership, from what the API returned

The decision panel is offered to the `REVIEWER` role on a `PENDING` request the
caller did not raise; amend and withdraw to the `RELATIONSHIP_MANAGER` role on a
`PENDING` request the caller did raise; nothing on a terminal request. Ownership
is `requestedBy` in the response compared with the current identity — data the
API returned, for a rule the API enforces (invariant 4 and the amend and withdraw
rules), so the page adds no rule of its own. The server still decides every
attempt and any refusal is shown as returned.

*Why not offer by role alone and let the server refuse:* it satisfies "the server
decides" but breaks "no approve control is offered" on a request a reviewer
reached directly, which is the one behaviour `CLAUDE.md` states outright.
*Consequence to state plainly:* a reviewer who raised a request cannot amend or
withdraw it from the page, because amend and withdraw follow the
`RELATIONSHIP_MANAGER` role; the API would allow it. That is the role-literal
reading, and relaxing it is a one-line change.

### A `409` is explained from its own fields; the sentences around them are fixed

`explainConflict` receives the problem, states that the request changed while it
was open, gives `currentVersion` and `currentStatus` from the response, shows the
service's `detail` verbatim, and offers a button that re-reads the request. It
serves the decision, the amendment and the withdrawal alike, since all three are
version-bound and a stale amend is the two-tabs case behind behaviour 5. If a
`409` carries no `currentVersion` (a conflict with no current state to report),
the page shows the `detail` alone rather than a version it does not have.

The fixed sentences are framing, not error text: they never replace or reword
what the service said, so requirement 6 and requirement 2 hold together.

### Writes are followed by a re-read

After any successful write the page re-reads `GET /requests/{id}` and renders
from that, rather than merging the write's response into what it already has.
The request and its history then always come from one response, which the API
returns together for that reason, and the page cannot show a request beside a
trail that has moved on.

### The page validates nothing

Forms are `novalidate` and inputs carry no `min`, `max`, `minlength` or `required`
attributes, so the bounds live in one place — `RequestLimits` — and a bad value
is refused in the service's words (required behaviour 6).
A number field's empty value is omitted from the body and a non-integer is sent
as typed, so the service, not the browser, decides what is wrong with it. An
amendment sends only the fields whose value differs from what is displayed,
which is what makes an unchanged submission the service's `400` and not a
version bump that alters nothing.

### The idempotency key is made once per opening, from `getRandomValues`

The key is set when the create form is opened, kept across every submission
attempt, and dropped when a create succeeds or the form is closed. It is built
from `crypto.getRandomValues`, formatted as a version-4 UUID, rather than
`crypto.randomUUID`, which exists only in secure contexts: `localhost` is one, but
a reviewer opening the service by a machine's address over plain HTTP would
otherwise get a page whose create button throws. The submit button is disabled
while a call is in flight so a double click is one call; the key makes it safe
anyway.

### Identity lives in `sessionStorage`, defaulting to a seeded requester

Per tab rather than per browser, because two tabs open as different identities is
how behaviours 2 and 5 are shown to someone: a requester amending in one tab
while a reviewer decides in another. Reads and writes are wrapped so a browser
that refuses storage still renders, with the default identity. A `<datalist>` of
the four seeded identifiers (`rm-1`, `rm-2`, `reviewer-1`, `reviewer-2`)
suggests values in the user-id field; the field accepts any text, because the
service does not know users and nothing should pretend it does. The default is
`rm-1` as `RELATIONSHIP_MANAGER`.

### Times are shown in UTC, from the string the service sent

Instants arrive as ISO-8601 in UTC and are displayed as `YYYY-MM-DD HH:MM UTC` by
reformatting the string, not by constructing a `Date`. That keeps the page from
converting to the viewer's zone, which would make the same history read
differently on two machines, against invariant 6.

### Basis points converted with integers

`bp` is shown beside `bps / 100` with two decimals, computed as a whole number of
hundredths (`Math.floor(bps / 100)` and the remainder, zero-padded), not by
floating-point division. The stored value is never a float; nor should its
rendering be one.

### Pico.css is committed, classless, and pinned

`static/vendor/pico.classless.min.css` at a pinned 2.x release, with its MIT
licence beside it and the version and source recorded in the README. It is
fetched once while implementing and never at runtime or build time. The classless
build keeps the markup semantic, which `CLAUDE.md` asks for. No class names
appear in `index.html`.

### Nothing on the server changes

Spring Boot serves `static/` and `index.html` at `/` by default, so there is no
controller, no configuration and no test change. The service's own unknown-path
problem detail is unaffected: `/` now resolves, and any other unknown path still
gets the pointer to `/swagger-ui.html`.

## Risks / Trade-offs

**Stored script injection through free text.** A reason, note or identity is
written by one user and rendered to another who can approve. → Text only, via one
helper with no markup path. Verified by creating a request whose reason is
`<img src=x onerror=alert(1)>` (long enough to pass validation) and seeing it
displayed as characters in the queue and the detail.

**A reviewer decides on a view that has gone stale.** The page shows what it read
when it was opened; the requester may amend after. → This is the design working:
the decision names the version shown and the service answers `409` with the
current one, which the page explains. The page deliberately does not poll or
refresh an open detail: a version that changed under a reviewer's eyes without
their noticing would defeat the binding the page exists to display. The 409 rate
from this page is therefore a measure of real concurrent edits, not of the
page's own behaviour.

**The offered actions drift from what the service permits.** The page reads
`requestedBy` and compares it with an identity it was told, so a service rule
that changed would leave the page offering a control the service refuses. → The
service is the authority and its refusal is displayed as returned, so drift
produces a visible `403` and never a wrong outcome. The page carries no rule that
is stricter than the service's, and only one — ownership — that mirrors it.

**The identity is trivially spoofable from a page that sets its own headers.**
That is the stub described in `CLAUDE.md` and already in the README's
omissions; the page makes it more visible, not larger.

**A vendored stylesheet is code nobody here wrote.** → Pinned version, licence
committed, source recorded, and never loaded from a third party at runtime.

**Verification is manual.** Criteria 7–9 have no automated test, by the choice in
`CLAUDE.md`, so a regression in the page would not fail the suite. → The README
says so, and the tasks name each check by criterion so it can be repeated.

## Migration Plan

Additive. `./mvnw spring-boot:run` serves the page at `/` whether or not the
`dev` profile is active; without the seed it is simply empty until a request is
created. Rollback is deleting `static/`.
