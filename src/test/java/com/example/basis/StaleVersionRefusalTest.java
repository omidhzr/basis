package com.example.basis;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.basis.request.ConflictException;
import com.example.basis.request.ExceptionRequest;
import com.example.basis.request.RequestStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * A rejection for a non-current version must tell the caller what the current
 * version is, so the request can be reviewed again rather than resubmitted
 * blind.
 *
 * <p>Only half of this rule is unit-testable. Whether a stale version is
 * refused at all is decided inside the guarded UPDATE, by design, so it cannot
 * be exercised without a database and is asserted through the API instead --
 * see the amend and decision tests in ExceptionRequestApiTest. What is
 * testable here, with no Spring context, is the other half: that the refusal
 * produced names the current version.
 */
class StaleVersionRefusalTest {

    @Test
    @DisplayName("a stale-version refusal names the current version")
    void staleVersionRefusalNamesTheCurrentVersion() {
        ExceptionRequest current = requestAt(RequestStatus.PENDING, 3);

        ProblemDetail problem = new ProblemDetailAdvice().onConflict(new ConflictException(current,
                "This request has moved on and is now at version " + current.version()
                        + ". Reload it and review the current version rather than resubmitting."));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getTitle()).isNotBlank();
        assertThat(problem.getDetail()).contains("version 3");
        assertThat(problem.getProperties()).containsEntry("currentVersion", 3);
    }

    @Test
    @DisplayName("a refusal on a terminal request says which state it reached")
    void terminalRefusalNamesTheStatus() {
        ExceptionRequest current = requestAt(RequestStatus.APPROVED, 2);

        ProblemDetail problem = new ProblemDetailAdvice().onConflict(new ConflictException(current,
                "This request is " + current.status() + " and can no longer be changed."));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getDetail()).contains("APPROVED");
        assertThat(problem.getProperties()).containsEntry("currentStatus", RequestStatus.APPROVED);
    }

    private static ExceptionRequest requestAt(RequestStatus status, int version) {
        Instant now = Instant.now();
        return new ExceptionRequest(
                UUID.randomUUID(), "APP-1", 25, "Retention case for a long-standing client",
                status, version, "rm-1", null, null, now, now);
    }
}
