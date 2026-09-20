package com.example.basis.request;

/** A refusal and the explanation a caller should be given for it. */
public record Refusal(RefusalReason reason, String detail) {
}
