package com.example.basis.request;

/**
 * The caller reused an idempotency key for a create that is not the one it
 * already stands for. Answers 422: the key is well-formed, and the request is
 * refused because of what the key already means.
 */
public class KeyReusedException extends RuntimeException {

    public KeyReusedException(String key) {
        super("The idempotency key '" + key + "' was already used for a different request. "
                + "Use a new key for a new request, or resend the original body to retrieve "
                + "the response it produced.");
    }
}
