package com.example.basis.request;

import java.util.UUID;

/**
 * The answer to a create, whether it created anything or replayed a create that
 * already happened. The body travels as the stored bytes so that a replay
 * returns what the first caller received rather than a response rebuilt from
 * current state.
 */
public record CreateOutcome(UUID requestId, int statusCode, String responseBody, boolean replayed) {
}
