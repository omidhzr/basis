package com.example.basis;

import com.example.basis.request.ConflictException;
import com.example.basis.request.RefusedException;
import com.example.basis.request.RequestNotFoundException;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Every non-2xx answer in one place, as RFC 7807. Later slices add their own
 * failures here rather than introducing a second shape for errors.
 *
 * <p>Each detail is written to be acted on: a stale version says what the
 * current version is and that the request must be reviewed again, rather than
 * inviting the caller to resubmit with the number they were just given.
 */
@RestControllerAdvice
public class ProblemDetailAdvice {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onInvalidBody(MethodArgumentNotValidException e) {
        String fields = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));

        return problem(HttpStatus.BAD_REQUEST, "Invalid request",
                fields.isEmpty() ? "The request body is not valid." : fields);
    }

    /**
     * Also covers a field the body has no business carrying: unknown
     * properties are rejected rather than ignored, so an attempt to change
     * something immutable fails loudly instead of appearing to succeed.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail onUnreadableBody(HttpMessageNotReadableException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request",
                "The request body could not be read. Only the fields this endpoint "
                        + "documents may be sent, and each must hold a permitted value.");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail onMissingHeader(MissingRequestHeaderException e) {
        return problem(HttpStatus.BAD_REQUEST, "Missing header",
                "The " + e.getHeaderName() + " header is required on every call.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail onMalformedPathValue(MethodArgumentTypeMismatchException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request",
                "The value given for " + e.getName() + " is not in the expected form.");
    }

    @ExceptionHandler(RefusedException.class)
    public ProblemDetail onRefused(RefusedException e) {
        return problem(HttpStatus.FORBIDDEN, "Not permitted", e.refusal().detail());
    }

    @ExceptionHandler(RequestNotFoundException.class)
    public ProblemDetail onNotFound(RequestNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "No such request", e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail onConflict(ConflictException e) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "Request has moved on", e.getMessage());
        if (e.current() != null) {
            problem.setProperty("currentVersion", e.current().version());
            problem.setProperty("currentStatus", e.current().status());
        }
        return problem;
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(title);
        problem.setDetail(detail);
        return problem;
    }
}
