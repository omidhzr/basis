package com.example.basis.request;

/**
 * An amendment that would change nothing. Answers 400.
 *
 * <p>Checked here rather than with a Bean Validation annotation because the
 * rule is about the body as a whole: expressed as a constraint it would name a
 * synthetic accessor in the message, and a caller reading "changesSomething
 * must be true" learns nothing they can act on.
 */
public class EmptyAmendmentException extends RuntimeException {

    public EmptyAmendmentException() {
        super("An amendment must carry a new discount or a new reason. A version change "
                + "that alters nothing still forces a reviewer to start again.");
    }
}
