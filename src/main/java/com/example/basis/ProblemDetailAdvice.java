package com.example.basis;

import com.example.basis.request.ConflictException;
import com.example.basis.request.EmptyAmendmentException;
import com.example.basis.request.KeyReusedException;
import com.example.basis.request.RefusedException;
import com.example.basis.request.RequestNotFoundException;
import java.util.stream.Collectors;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Every non-2xx answer in one place, as RFC 7807. Later slices add their own
 * failures here rather than introducing a second shape for errors.
 *
 * <p>Each detail is written to be acted on: a stale version says what the
 * current version is and that the request must be reviewed again, rather than
 * inviting the caller to resubmit with the number they were just given.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} brings the rejections
 * Spring raises before any handler runs -- an unsupported content type, an
 * unsupported method, an unknown path -- into this class rather than leaving
 * them to the default error page, which answers with no title and no detail.
 * The two validation methods below are overrides for that reason: declaring
 * them again as their own handlers would map one exception type twice in one
 * bean.
 */
@RestControllerAdvice
public class ProblemDetailAdvice extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        String fields = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));

        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "Invalid request",
                fields.isEmpty() ? "The request body is not valid." : fields));
    }

    /**
     * Also covers a field the body has no business carrying: unknown
     * properties are rejected rather than ignored, so an attempt to change
     * something immutable fails loudly instead of appearing to succeed.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException e, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "Invalid request",
                "The request body could not be read. Only the fields this endpoint "
                        + "documents may be sent, and each must hold a permitted value."));
    }

    /**
     * Spring's own detail for an unknown path names a missing static resource,
     * which is true and useless: this service serves an API, and a caller who
     * mistyped a path needs to be pointed at the ones that exist.
     */
    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(
            NoResourceFoundException e, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                problem(HttpStatus.NOT_FOUND, "No such endpoint",
                        "This service has no endpoint at /" + e.getResourcePath()
                                + ". The paths it offers are listed at /swagger-ui.html."));
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
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException e, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        String problems = e.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> describe(result, error)))
                .collect(Collectors.joining("; "));

        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "Invalid request",
                problems.isEmpty() ? "A value supplied with this call is not valid." : problems));
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

    @ExceptionHandler(EmptyAmendmentException.class)
    public ProblemDetail onEmptyAmendment(EmptyAmendmentException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage());
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
