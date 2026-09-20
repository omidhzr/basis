package com.example.basis.request;

/**
 * Field bounds, in one place so that validation and the database constraint
 * cannot disagree. The discount range in particular is a policy decision
 * rather than a known business rule, so it is named rather than repeated as a
 * literal wherever it is enforced.
 */
public final class RequestLimits {

    public static final int MIN_DISCOUNT_BPS = 1;
    public static final int MAX_DISCOUNT_BPS = 200;

    public static final int MIN_REASON_LENGTH = 10;
    public static final int MAX_REASON_LENGTH = 500;

    public static final int MAX_APPLICATION_ID_LENGTH = 64;
    public static final int MAX_IDENTITY_LENGTH = 128;
    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private RequestLimits() {
    }
}
