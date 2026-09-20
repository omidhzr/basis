package com.example.basis.request;

import java.time.Instant;
import java.util.UUID;

/**
 * One transition, as recorded. The version is the version this entry
 * concerned, which is not always the request's current version, and the
 * payload carries what that transition carried -- the discount and reason for
 * CREATED and AMENDED, the reviewer's note for a decision.
 */
public record HistoryEntry(
        UUID id,
        UUID requestId,
        EntryType entryType,
        int version,
        String actor,
        String payload,
        Instant occurredAt) {
}
