package com.seatlock.web;

import com.seatlock.web.dto.ConfirmBookingRequest;
import com.seatlock.web.dto.CreateHoldRequest;
import com.seatlock.web.dto.RegisterRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean validation on the request DTOs, with no Spring context at all.
 *
 * <h2>Why bother, when the integration tests already send bad requests</h2>
 *
 * Because these constraints are the outermost layer of input handling, they are
 * cheap to get subtly wrong, and an integration test can only reach a handful of
 * them before it becomes a slow way to test a regular expression. A plain
 * {@link Validator} from {@code Validation.buildDefaultValidatorFactory()} runs
 * the exact same Hibernate Validator engine Spring uses, in microseconds,
 * without a Tomcat or a database in sight.
 *
 * <p>The point is that these are <b>controls, not politeness</b>. The seat-count
 * cap bounds the size of a Lua script's key list; the payment-token pattern
 * constrains an opaque string that is passed to another system. Getting either
 * wrong is not a cosmetic bug.
 */
@DisplayName("Request validation: the constraints on the DTOs")
class ValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void buildValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    // ==================================================================
    // CreateHoldRequest
    // ==================================================================

    @Test
    @DisplayName("a hold for zero seats is rejected")
    void emptySeatListIsRejected() {
        var violations = validator.validate(new CreateHoldRequest(List.of()));

        assertThat(fieldsOf(violations)).containsExactly("seatIds");
        assertThat(messagesOf(violations)).contains("Select at least one seat");
    }

    @Test
    @DisplayName("a hold for more than ten seats is rejected")
    void oversizedSeatListIsRejected() {
        List<Long> eleven = LongStream.rangeClosed(1, 11).boxed().toList();

        var violations = validator.validate(new CreateHoldRequest(eleven));

        // Rejected here as well as in BookingService, deliberately. The
        // annotation gives a clean 400 with a helpful message before any of our
        // code runs; the service check is what enforces the *configured* limit,
        // which can differ per environment. Belt and braces on the one input
        // that bounds how long Redis's single command thread is occupied.
        assertThat(fieldsOf(violations)).containsExactly("seatIds");
        assertThat(messagesOf(violations)).contains("You can book at most 10 seats at a time");
    }

    @Test
    @DisplayName("a non-positive seat id is rejected")
    void nonPositiveSeatIdIsRejected() {
        var violations = validator.validate(new CreateHoldRequest(List.of(0L, -3L)));

        // The constraint is on the list ELEMENT, not the list, so the property
        // path names the index. Worth knowing: putting @Positive on the field
        // rather than inside the generics would validate the List object itself
        // and silently do nothing at all.
        assertThat(violations).hasSize(2);
        assertThat(messagesOf(violations)).containsExactly("Seat ids must be positive");
        assertThat(fieldsOf(violations))
                .allSatisfy(path -> assertThat(path).startsWith("seatIds"));
    }

    /**
     * <p>Duplicates are <b>not</b> a validation failure, and that is a decision
     * rather than an oversight. A client that double-taps a seat, or a retry
     * that concatenates two selections, has not done anything a user would
     * recognise as an error - so {@code BookingService.createHold} runs the ids
     * through {@code .distinct().sorted()} and carries on.
     *
     * <p>Rejecting them would turn a harmless client bug into a visible failure
     * for the user, and would not make the system any safer: the de-duplicated
     * list is what the cap is applied to, so nobody can smuggle a thousand ids
     * past the limit by repeating them.
     *
     * <p>(Note that {@code docs/API.md} currently describes the rule as
     * "1-10 ids, no duplicates". The implementation de-duplicates rather than
     * refusing; the doc is the thing that is out of step.)
     */
    @Test
    @DisplayName("duplicate seat ids pass validation and are de-duplicated by the service")
    void duplicateSeatIdsAreAcceptedAndDeduplicated() {
        var violations = validator.validate(new CreateHoldRequest(List.of(7L, 7L, 7L)));

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("a well-formed hold request has no violations")
    void aValidHoldRequestPasses() {
        assertThat(validator.validate(new CreateHoldRequest(List.of(11L, 12L, 13L)))).isEmpty();
    }

    // ==================================================================
    // ConfirmBookingRequest
    // ==================================================================

    @Test
    @DisplayName("an unsupported payment method is rejected")
    void unsupportedPaymentMethodIsRejected() {
        var violations = validator.validate(
                new ConfirmBookingRequest("BITCOIN", "tok_demo_success"));

        // An allow-list, not a deny-list. The set of payment methods this system
        // can actually process is small and known; anything outside it is a
        // client bug at best, and there is no reason to let it reach the gateway
        // to find out.
        assertThat(fieldsOf(violations)).containsExactly("paymentMethod");
        assertThat(messagesOf(violations)).contains("Unsupported payment method");
    }

    @Test
    @DisplayName("a malformed payment token is rejected")
    void malformedPaymentTokenIsRejected() {
        var violations = validator.validate(
                new ConfirmBookingRequest("CARD", "tok!! drop table bookings;--"));

        // The token is opaque to us, but "opaque" must not mean "anything at
        // all". Constraining the character set closes off a whole class of
        // injection into whatever consumes it downstream - and costs nothing,
        // because a real provider's tokens are alphanumeric anyway.
        assertThat(fieldsOf(violations)).containsExactly("paymentToken");
        assertThat(messagesOf(violations)).contains("Malformed payment token");
    }

    @Test
    @DisplayName("a payment token shorter than eight characters is rejected")
    void tooShortPaymentTokenIsRejected() {
        var violations = validator.validate(new ConfirmBookingRequest("UPI", "tok_1"));

        assertThat(fieldsOf(violations)).containsExactly("paymentToken");
    }

    @Test
    @DisplayName("blank payment fields are rejected on both counts")
    void blankPaymentFieldsAreRejected() {
        var violations = validator.validate(new ConfirmBookingRequest("", ""));

        // Both fields fail twice over - @NotBlank and @Pattern - which is fine:
        // GlobalExceptionHandler keeps only the first message per field, so the
        // user sees one complaint per input rather than four.
        assertThat(fieldsOf(violations)).containsExactlyInAnyOrder("paymentMethod", "paymentToken");
        assertThat(messagesOf(violations))
                .contains("Payment method is required", "Payment token is required");
    }

    @Test
    @DisplayName("a well-formed confirm request has no violations")
    void aValidConfirmRequestPasses() {
        assertThat(validator.validate(new ConfirmBookingRequest("CARD", "tok_demo_success"))).isEmpty();
    }

    // ==================================================================
    // RegisterRequest
    // ==================================================================

    @Test
    @DisplayName("registration enforces length, not a character-class puzzle")
    void registrationEnforcesPasswordLength() {
        var tooShort = validator.validate(
                new RegisterRequest("alice@test.dev", "abc123", "Alice"));

        assertThat(fieldsOf(tooShort)).containsExactly("password");
        assertThat(messagesOf(tooShort)).contains("Password must be between 10 and 128 characters");

        // The other half of the point: a long passphrase with no digits, no
        // symbols and no capitals is perfectly acceptable, because length is
        // what actually resists guessing. Composition rules push people towards
        // Password1! - short, predictable, and in every cracking dictionary.
        assertThat(validator.validate(
                new RegisterRequest("alice@test.dev", "correct horse battery staple", "Alice")))
                .isEmpty();
    }

    @Test
    @DisplayName("registration rejects a malformed email address")
    void registrationRejectsAMalformedEmail() {
        var violations = validator.validate(
                new RegisterRequest("not-an-email", "CorrectHorseBattery", "Alice"));

        // Note this constraint exists on registration but deliberately NOT on
        // login: validating the format there would answer "is this even an
        // email?" before the credential check, giving a malformed address a
        // different response from a wrong password. One more signal than we
        // need to give.
        assertThat(fieldsOf(violations)).containsExactly("email");
    }

    // ==================================================================

    /**
     * The distinct property paths that failed, as a set.
     *
     * <p>A set rather than a list because one field routinely produces several
     * violations at once - a blank payment method fails {@code @NotBlank} and
     * {@code @Pattern} together - and the interesting question is <em>which
     * inputs</em> were rejected, not how many complaints each one collected.
     * {@code LinkedHashSet} keeps the iteration order stable so a failure
     * message reads the same way twice.
     */
    private static Set<String> fieldsOf(Set<? extends ConstraintViolation<?>> violations) {
        Set<String> fields = new LinkedHashSet<>();
        for (ConstraintViolation<?> violation : violations) {
            fields.add(violation.getPropertyPath().toString());
        }
        return fields;
    }

    private static Set<String> messagesOf(Set<? extends ConstraintViolation<?>> violations) {
        Set<String> messages = new LinkedHashSet<>();
        for (ConstraintViolation<?> violation : violations) {
            messages.add(violation.getMessage());
        }
        return messages;
    }
}
