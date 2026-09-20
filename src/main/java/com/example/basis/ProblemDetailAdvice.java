package com.example.basis;

import com.example.basis.request.ConflictException;
import com.example.basis.request.KeyReusedException;
import com.example.basis.request.RefusedException;
import com.example.basis.request.RequestNotFoundException;
import java.util.stream.Collectors;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
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

    /**
     * Raised when a bound on a method parameter fails -- the Idempotency-Key,
     * validated where it is read rather than in a body record.
     *
     * <p>From Spring 6.1 a constrained parameter anywhere on the method routes
     * that method's body validation here too, so this names the values that
     * actually failed instead of assuming which one did.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail onInvalidParameter(HandlerMethodValidationException e) {
        String problems = e.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> describe(result, error)))
                .collect(Collectors.joining("; "));

        return problem(HttpStatus.BAD_REQUEST, "Invalid request",
                problems.isEmpty() ? "A value supplied with this call is not valid." : problems);
    }

    /**
     * Names the value that failed as the caller would recognise it: the field
     * inside a body, otherwise the header as it is spelled on the wire. Saying
     * only "body" would leave the caller to guess which field was wrong.
     */
    private static String describe(ParameterValidationResult result, MessageSourceResolvable error) {
        if (error instanceof FieldError field) {
            return field.getField() + " " + field.getDefaultMessage();
        }
        RequestHeader header = result.getMethodParameter().getParameterAnnotation(RequestHeader.class);
        String name = header != null && !header.value().isEmpty()
                ? header.value()
                : result.getMethodParameter().getParameterName();
        return name + " " + error.getDefaultMessage();
    }

    @ExceptionHandler(RefusedException.class)
    public ProblemDetail onRefused(RefusedException e) {
        return problem(HttpStatus.FORBIDDEN, "Not permitted", e.refusal().detail());
    }

    /**
     * The key is well-formed; what it already stands for is the problem. That
     * is 422 rather than 409: nothing about the request's state conflicts, and
     * resubmitting the same body against the same key will never succeed.
     */
    @ExceptionHandler(KeyReusedException.class)
    public ProblemDetail onKeyReused(KeyReusedException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency key already used", e.getMessage());
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
