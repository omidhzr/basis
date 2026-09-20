package com.example.basis.request;

/**
 * The write matched no row: the request had moved on. Answers 409, carrying
 * the state found when the failure was classified.
 */
public class ConflictException extends RuntimeException {

    private final transient ExceptionRequest current;

    public ConflictException(ExceptionRequest current, String detail) {
        super(detail);
        this.current = current;
    }

    public ExceptionRequest current() {
        return current;
    }
}
