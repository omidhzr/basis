package com.example.basis.request;

/**
 * Why a caller may not perform an action. Every reason here is a property of
 * the caller and the request's stored identity, never of its version or
 * status -- those race, so they are guarded inside the UPDATE statement
 * instead. All of these answer 403.
 */
public enum RefusalReason {

    NOT_REVIEWER,
    SELF_DECISION,
    NOT_REQUESTER
}
