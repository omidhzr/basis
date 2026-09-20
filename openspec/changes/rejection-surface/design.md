# Design

## Context

See proposal.md for motivation. All three fixes land in code that already
exists: the one `@RestControllerAdvice`, the identity headers read at each call
site, and the amend body record.

Two answers were settled by the business rather than chosen here: an amendment
carrying neither field is refused, and `X-User-Id` is bounded at 128 characters,
inherited from the column width. Both are recorded in the README as decisions
that a real identity provider and a real policy would revisit.

## Goals / Non-Goals

**Goals:**

- No caller input reaches a `500`.
- Every rejection looks the same to a client, whoever raised it.
- The existing `400` details keep naming the field that failed.

**Non-Goals:**

- A second advice, a filter, or an argument resolver. CLAUDE.md says one
  `@RestControllerAdvice`, and this change should leave that true.
- Anything about authentication. The identity bound is a storage bound, not a
  security control.

## Decisions

### The one advice extends `ResponseEntityExceptionHandler`

Spring raises its own rejections -- unsupported content type, unsupported
method, unknown path -- before any handler runs, so an advice that only names
the service's own exceptions never sees them. They reach the default error page
instead and answer with `{"timestamp", "status", "error", "path"}`: no title,
no detail, and `application/json` rather than `application/problem+json`.

Extending `ResponseEntityExceptionHandler` brings all of them into the advice
that already exists, and in Spring 6 that base class already produces
`ProblemDetail` bodies.

*Alternative considered:* `spring.mvc.problemdetails.enabled=true`, which is one
line and no code. Rejected because it works by registering a *second* advice
alongside ours. CLAUDE.md says structured errors come from one
`@RestControllerAdvice`, and two advices that both claim `MethodArgumentNotValid`
and `HandlerMethodValidation` would leave which detail a caller sees depending
on bean ordering -- exactly the kind of thing that is invisible until it is
wrong.

The consequence is that our two existing validation handlers must become
`@Override`s of the base class's `handleMethodArgumentNotValid` and
`handleHandlerMethodValidationException` rather than stay as their own
`@ExceptionHandler` methods. Declaring both would be an ambiguous mapping for
the same exception type in the same bean. The detail text they produce does not
change, and the existing tests assert that text.

### The identity bound is an annotation at each call site

`@Size(max = RequestLimits.MAX_IDENTITY_LENGTH)` on the `@RequestHeader("X-User-Id")`
parameter of all four endpoints, using the constant that has sat unused since
`request-lifecycle` defined it. This is the mechanism the `Idempotency-Key`
already uses, so there is one way a header bound is expressed rather than two.

Repeating the annotation four times is the cost of reading the headers at each
call site, which CLAUDE.md asks for so the identity stub stays visible. A filter
or an argument resolver would remove the repetition and hide the stub, which is
the trade it was set up to avoid.

### An empty amendment is refused in the controller, not by an annotation

Bean Validation expresses a cross-field rule as `@AssertTrue` on a synthetic
accessor, which works but names that accessor in the message a caller reads:
"changesSomething must be true" explains nothing to the person who sent the
request.

The check is one line where the amend body arrives, raising an exception the
advice answers as `400` with a written sentence. Field bounds stay on the record
with the others; only this rule, which is about the body as a whole rather than
about any field in it, sits beside them rather than among them.

## Risks / Trade-offs

**Extending the base class changes how existing rejections are produced.** The
four `400` paths that `request-lifecycle` tests all flow through the two
overridden methods now. → The existing suite asserts those details, including
that each names its field, and it runs before this change is committed. If a
detail changes, a test says so rather than a caller discovering it.

**The 128-character identity bound is inherited, not specified.** Nothing in the
brief says how long a user identifier may be; the number comes from the column
the value is stored in. → Recorded in the README as an open question, next to
the note that real authentication would carry the identity as a verified claim
and make the bound the provider's business rather than the service's.

**Refusing an empty amendment removes a way to force re-review.** An RM who
wanted to pull a request back from a reviewer by bumping its version can no
longer do so. → No workflow in the brief asks for that, and withdrawal already
exists for taking a request back. Recorded as the open question it is.
