package com.example.basis.request;

import java.time.Instant;
import java.util.UUID;

/**
 * What one create produced, kept so that a retry can be answered with the same
 * response rather than a second request.
 *
 * <p>The response is stored as the bytes that were returned, not as a reference
 * to the request row: the row moves when the request is amended, and a retry of
 * the original create must still be recognised as that create.
 */
public record IdempotencyRecord(
        String key,
        String caller,
        UUID requestId,
        String responseBody,
        int statusCode,
        Instant createdAt) {
}
