package com.example.basis.request;

import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the mortgage application process consumes -- acceptance criterion 6.
 * The only endpoint whose caller is a system rather than a person, and it
 * carries identity like every other call: one rule about identity is easier to
 * keep true than one rule with an exception, and it leaves the audit story
 * intact when real authentication replaces the stub.
 *
 * <p>Separate from the request controller because it answers about an
 * application rather than about a request. The consumer asks "what discount
 * applies to this application?" without knowing that requests exist, let alone
 * which one won.
 */
@RestController
@RequestMapping("/applications")
public class ApprovedExceptionController {

    private final ExceptionRequestStore store;

    public ApprovedExceptionController(ExceptionRequestStore store) {
        this.store = store;
    }

    @GetMapping("/{id}/approved-exception")
    public RequestBodies.ApprovedException approvedException(
            @PathVariable String id,
            @RequestHeader("X-User-Id")
            @Size(max = RequestLimits.MAX_IDENTITY_LENGTH,
                  message = "must be at most {max} characters")
            String userId,
            @RequestHeader("X-User-Role") UserRole role) {

        return store.approvedExceptionFor(id)
                .map(RequestBodies.ApprovedException::of)
                .orElseThrow(() -> new ApprovedExceptionNotFoundException(id));
    }
}
