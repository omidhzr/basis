package com.example.basis.request;

/** What a reviewer submits. Distinct from the status it produces. */
public enum Decision {

    APPROVE(RequestStatus.APPROVED, EntryType.APPROVED),
    DECLINE(RequestStatus.DECLINED, EntryType.DECLINED);

    private final RequestStatus status;
    private final EntryType entryType;

    Decision(RequestStatus status, EntryType entryType) {
        this.status = status;
        this.entryType = entryType;
    }

    public RequestStatus status() {
        return status;
    }

    public EntryType entryType() {
        return entryType;
    }
}
