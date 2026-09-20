package com.example.basis.request;

/** The caller may not do this, on the evidence of who they are. Answers 403. */
public class RefusedException extends RuntimeException {

    private final transient Refusal refusal;

    public RefusedException(Refusal refusal) {
        super(refusal.detail());
        this.refusal = refusal;
    }

    public Refusal refusal() {
        return refusal;
    }
}
