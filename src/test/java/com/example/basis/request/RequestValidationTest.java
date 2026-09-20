package com.example.basis.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The bounds in CLAUDE.md's validation table, asserted at both edges: the
 * value that must be accepted and the one just outside it that must not. A
 * bound is only specified if both sides of it are.
 *
 * <p>Bean Validation is driven directly here rather than through MockMvc, so
 * the suite needs no Spring context. That a violation becomes a 400 is a
 * separate claim, asserted through the API.
 */
class RequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void startValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void stopValidator() {
        factory.close();
    }

    @Test
    @DisplayName("a discount of 1 and of 200 basis points is accepted")
    void discountAtBothBoundsIsAccepted() {
        assertThat(validator.validate(create("APP-1", 1, reasonOf(20)))).isEmpty();
        assertThat(validator.validate(create("APP-1", 200, reasonOf(20)))).isEmpty();
    }

    @Test
    @DisplayName("a discount of 0 or of 201 basis points is refused")
    void discountOutsideTheBoundsIsRefused() {
        assertThat(validator.validate(create("APP-1", 0, reasonOf(20)))).isNotEmpty();
        assertThat(validator.validate(create("APP-1", 201, reasonOf(20)))).isNotEmpty();
    }

    @Test
    @DisplayName("a missing discount is refused")
    void discountIsRequired() {
        assertThat(validator.validate(create("APP-1", null, reasonOf(20)))).isNotEmpty();
    }

    @Test
    @DisplayName("a reason of 10 and of 500 characters is accepted")
    void reasonAtBothBoundsIsAccepted() {
        assertThat(validator.validate(create("APP-1", 25, reasonOf(10)))).isEmpty();
        assertThat(validator.validate(create("APP-1", 25, reasonOf(500)))).isEmpty();
    }

    @Test
    @DisplayName("a reason of 9 or of 501 characters is refused")
    void reasonOutsideTheBoundsIsRefused() {
        assertThat(validator.validate(create("APP-1", 25, reasonOf(9)))).isNotEmpty();
        assertThat(validator.validate(create("APP-1", 25, reasonOf(501)))).isNotEmpty();
    }

    @Test
    @DisplayName("a blank reason is refused")
    void reasonIsRequired() {
        assertThat(validator.validate(create("APP-1", 25, "   "))).isNotEmpty();
        assertThat(validator.validate(create("APP-1", 25, null))).isNotEmpty();
    }

    @Test
    @DisplayName("an application identifier of 64 characters is accepted, 65 is refused")
    void applicationIdentifierIsBounded() {
        assertThat(validator.validate(create("A".repeat(64), 25, reasonOf(20)))).isEmpty();
        assertThat(validator.validate(create("A".repeat(65), 25, reasonOf(20)))).isNotEmpty();
    }

    @Test
    @DisplayName("a blank application identifier is refused")
    void applicationIdentifierIsRequired() {
        assertThat(validator.validate(create("   ", 25, reasonOf(20)))).isNotEmpty();
        assertThat(validator.validate(create(null, 25, reasonOf(20)))).isNotEmpty();
    }

    @Test
    @DisplayName("an amendment carries the same bounds, and its version must be positive")
    void amendmentIsBoundedTheSameWay() {
        assertThat(validator.validate(new RequestBodies.Amend(1, reasonOf(10), 1))).isEmpty();
        assertThat(validator.validate(new RequestBodies.Amend(200, reasonOf(500), 9))).isEmpty();

        assertThat(validator.validate(new RequestBodies.Amend(0, reasonOf(20), 1))).isNotEmpty();
        assertThat(validator.validate(new RequestBodies.Amend(201, reasonOf(20), 1))).isNotEmpty();
        assertThat(validator.validate(new RequestBodies.Amend(25, reasonOf(9), 1))).isNotEmpty();
        assertThat(validator.validate(new RequestBodies.Amend(25, reasonOf(501), 1))).isNotEmpty();

        // Either field may be omitted -- an amendment may change one or both.
        assertThat(validator.validate(new RequestBodies.Amend(25, null, 1))).isEmpty();
        assertThat(validator.validate(new RequestBodies.Amend(null, reasonOf(20), 1))).isEmpty();

        assertThat(validator.validate(new RequestBodies.Amend(25, reasonOf(20), null))).isNotEmpty();
        assertThat(validator.validate(new RequestBodies.Amend(25, reasonOf(20), 0))).isNotEmpty();
    }

    @Test
    @DisplayName("a decision and a withdrawal each require a positive version")
    void decisionAndWithdrawalRequireAVersion() {
        assertThat(validator.validate(new RequestBodies.Decide(Decision.APPROVE, 1, null))).isEmpty();
        assertThat(validator.validate(new RequestBodies.Decide(Decision.APPROVE, 0, null))).isNotEmpty();
        assertThat(validator.validate(new RequestBodies.Decide(Decision.APPROVE, null, null))).isNotEmpty();
        assertThat(validator.validate(new RequestBodies.Decide(null, 1, null))).isNotEmpty();

        assertThat(validator.validate(new RequestBodies.Withdraw(1))).isEmpty();
        assertThat(validator.validate(new RequestBodies.Withdraw(0))).isNotEmpty();
        assertThat(validator.validate(new RequestBodies.Withdraw(null))).isNotEmpty();
    }

    @Test
    @DisplayName("the bounds asserted here are the ones the service names")
    void boundsComeFromTheNamedConstants() {
        // Guards against the table and the constants drifting apart: these
        // tests state the numbers from CLAUDE.md, the service states them once.
        assertThat(RequestLimits.MIN_DISCOUNT_BPS).isEqualTo(1);
        assertThat(RequestLimits.MAX_DISCOUNT_BPS).isEqualTo(200);
        assertThat(RequestLimits.MIN_REASON_LENGTH).isEqualTo(10);
        assertThat(RequestLimits.MAX_REASON_LENGTH).isEqualTo(500);
        assertThat(RequestLimits.MAX_APPLICATION_ID_LENGTH).isEqualTo(64);
    }

    private static RequestBodies.Create create(String applicationId, Integer discountBps, String reason) {
        return new RequestBodies.Create(applicationId, discountBps, reason);
    }

    private static String reasonOf(int length) {
        return "R".repeat(length);
    }
}
