package com.example.basis.request;

import java.util.UUID;

/** No request with that identifier exists. Answers 404. */
public class RequestNotFoundException extends RuntimeException {

    public RequestNotFoundException(UUID id) {
        super("No exception request with id " + id + ".");
    }
}
