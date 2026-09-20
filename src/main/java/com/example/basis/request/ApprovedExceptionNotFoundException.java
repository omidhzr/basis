package com.example.basis.request;

/**
 * No approved exception applies to this application. Answers 404.
 *
 * <p>A 404 rather than a discount of zero: "nothing was approved" and "a
 * discount of nothing was approved" are different answers, and a consumer that
 * cannot tell them apart will eventually price one as the other.
 */
public class ApprovedExceptionNotFoundException extends RuntimeException {

    public ApprovedExceptionNotFoundException(String applicationId) {
        super("No approved pricing exception applies to application " + applicationId
                + ". The standard rate applies unless a request for it is approved.");
    }
}
