package com.example.basis.request;

/**
 * The state of a request. Values are fixed by the domain and are stored as
 * written here.
 */
public enum RequestStatus {

    PENDING,
    APPROVED,
    DECLINED,
    WITHDRAWN;

    public boolean isTerminal() {
        return this != PENDING;
    }

    /**
     * Whether a transition is legal from this state.
     *
     * <p>This does not gate any write. The version and status guards live
     * inside the UPDATE statement, because they race. This answers why a
     * guarded write matched no row, once the row has been re-read: a terminal
     * request explains the rejection differently from a stale version.
     */
    public boolean allows(EntryType transition) {
        return this == PENDING && transition != EntryType.CREATED;
    }
}
