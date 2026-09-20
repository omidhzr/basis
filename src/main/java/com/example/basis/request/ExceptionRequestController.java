package com.example.basis.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Identity arrives as the X-User-Id and X-User-Role headers on every call.
 * These stand in for claims that a JWT would carry in production; nothing
 * verifies that a caller is who the headers claim. Authorisation is real and
 * is enforced below and in the store -- only authentication is stubbed, and
 * the headers are read at each call site rather than hidden behind a filter so
 * that the stub stays visible wherever it is relied on.
 */
@RestController
@RequestMapping("/requests")
public class ExceptionRequestController {

    private final ExceptionRequestStore store;

    public ExceptionRequestController(ExceptionRequestStore store) {
        this.store = store;
    }

    /**
     * The response body travels as the stored bytes rather than as an object,
     * so that a replay returns what the first caller received rather than a
     * response rebuilt from state that may have moved since.
     */
    @PostMapping
    public ResponseEntity<String> create(
            @RequestHeader("X-User-Id")
            @Size(max = RequestLimits.MAX_IDENTITY_LENGTH,
                  message = "must be at most {max} characters")
            String userId,
            @RequestHeader("X-User-Role") UserRole role,
            @RequestHeader("Idempotency-Key")
            @NotBlank
            @Size(max = RequestLimits.MAX_IDEMPOTENCY_KEY_LENGTH,
                  message = "must be at most {max} characters")
            String idempotencyKey,
            @Valid @RequestBody RequestBodies.Create body) {

        CreateOutcome outcome = store.create(
                body.applicationId(), body.discountBps(), body.reason(), userId, idempotencyKey);

        return ResponseEntity
                .status(outcome.statusCode())
                .location(URI.create("/requests/" + outcome.requestId()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(outcome.responseBody());
    }

    /**
     * The queue, whose contents depend on who is asking. A reviewer is offered
     * work raised by others; anyone else sees their own. Binding status to the
     * enum means a value outside the four is the 400 the advice already
     * answers, with no check written here.
     */
    @GetMapping
    public List<RequestBodies.View> queue(
            @RequestHeader("X-User-Id")
            @Size(max = RequestLimits.MAX_IDENTITY_LENGTH,
                  message = "must be at most {max} characters")
            String userId,
            @RequestHeader("X-User-Role") UserRole role,
            @RequestParam(required = false) RequestStatus status) {

        List<ExceptionRequest> queue = role == UserRole.REVIEWER
                ? store.queueForReviewer(userId, status)
                : store.queueForRequester(userId, status);

        return queue.stream().map(RequestBodies.View::of).toList();
    }

    /**
     * The request and its trail in one response. No role is checked: the two
     * roles this service has are the only callers there are, so a check would
     * restrict nothing -- see the open question in the README.
     */
    @GetMapping("/{id}")
    public RequestBodies.Detail read(
            @PathVariable UUID id,
            @RequestHeader("X-User-Id")
            @Size(max = RequestLimits.MAX_IDENTITY_LENGTH,
                  message = "must be at most {max} characters")
            String userId,
            @RequestHeader("X-User-Role") UserRole role) {

        return store.detailOf(id);
    }

    @PatchMapping("/{id}")
    public RequestBodies.View amend(
            @PathVariable UUID id,
            @RequestHeader("X-User-Id")
            @Size(max = RequestLimits.MAX_IDENTITY_LENGTH,
                  message = "must be at most {max} characters")
            String userId,
            @RequestHeader("X-User-Role") UserRole role,
            @Valid @RequestBody RequestBodies.Amend body) {

        if (body.discountBps() == null && body.reason() == null) {
            throw new EmptyAmendmentException();
        }

        return RequestBodies.View.of(
                store.amend(id, body.version(), userId, body.discountBps(), body.reason()));
    }

    @PostMapping("/{id}/decision")
    public RequestBodies.View decide(
            @PathVariable UUID id,
            @RequestHeader("X-User-Id")
            @Size(max = RequestLimits.MAX_IDENTITY_LENGTH,
                  message = "must be at most {max} characters")
            String userId,
            @RequestHeader("X-User-Role") UserRole role,
            @Valid @RequestBody RequestBodies.Decide body) {

        return RequestBodies.View.of(
                store.decide(id, body.version(), userId, role, body.decision(), body.note()));
    }

    @PostMapping("/{id}/withdrawal")
    public RequestBodies.View withdraw(
            @PathVariable UUID id,
            @RequestHeader("X-User-Id")
            @Size(max = RequestLimits.MAX_IDENTITY_LENGTH,
                  message = "must be at most {max} characters")
            String userId,
            @RequestHeader("X-User-Role") UserRole role,
            @Valid @RequestBody RequestBodies.Withdraw body) {

        return RequestBodies.View.of(store.withdraw(id, body.version(), userId));
    }
}
