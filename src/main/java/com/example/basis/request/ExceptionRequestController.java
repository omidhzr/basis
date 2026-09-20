package com.example.basis.request;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
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

    @PostMapping
    public ResponseEntity<RequestBodies.View> create(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-User-Role") UserRole role,
            @Valid @RequestBody RequestBodies.Create body) {

        ExceptionRequest created = store.create(
                body.applicationId(), body.discountBps(), body.reason(), userId);

        return ResponseEntity
                .created(URI.create("/requests/" + created.id()))
                .body(RequestBodies.View.of(created));
    }

    @PatchMapping("/{id}")
    public RequestBodies.View amend(
            @PathVariable UUID id,
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-User-Role") UserRole role,
            @Valid @RequestBody RequestBodies.Amend body) {

        return RequestBodies.View.of(
                store.amend(id, body.version(), userId, body.discountBps(), body.reason()));
    }

    @PostMapping("/{id}/decision")
    public RequestBodies.View decide(
            @PathVariable UUID id,
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-User-Role") UserRole role,
            @Valid @RequestBody RequestBodies.Decide body) {

        return RequestBodies.View.of(
                store.decide(id, body.version(), userId, role, body.decision(), body.note()));
    }

    @PostMapping("/{id}/withdrawal")
    public RequestBodies.View withdraw(
            @PathVariable UUID id,
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-User-Role") UserRole role,
            @Valid @RequestBody RequestBodies.Withdraw body) {

        return RequestBodies.View.of(store.withdraw(id, body.version(), userId));
    }
}
