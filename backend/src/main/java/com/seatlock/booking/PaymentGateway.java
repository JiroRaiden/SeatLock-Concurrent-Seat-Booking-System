package com.seatlock.booking;

/**
 * The payment provider, as this application sees it.
 *
 * <p>An interface with a stub implementation, rather than payment code inlined
 * into {@code BookingService}. Three reasons, all of which are worth saying out
 * loud:
 *
 * <ol>
 *   <li><b>Tests.</b> Every booking test can inject a gateway that declines, or
 *       times out, or throws - without a network, an API key, or a sandbox
 *       account. The declined-payment path is otherwise almost impossible to
 *       exercise, which is why it is so often the one that is broken.</li>
 *   <li><b>Scope.</b> The rest of the system depends on this shape, not on
 *       Razorpay or Stripe. Swapping providers is one class.</li>
 *   <li><b>Honesty.</b> Naming this a stub in the type system is better than a
 *       method called {@code processPayment} that silently returns true. Nobody
 *       reading the code can mistake what it does.</li>
 * </ol>
 *
 * <h2>Authorize then capture, not "charge"</h2>
 *
 * Real card payments are two steps. <b>Authorize</b> reserves the money on the
 * customer's card and returns a reference; <b>capture</b> actually moves it. In
 * between, you can <b>void</b> the authorization and nothing is taken.
 *
 * <p>That split exists precisely for a situation like ours: we must not take
 * money for a booking that then fails to save. So the sequence is authorize,
 * commit the booking to Postgres, capture. If the commit fails, we void, and the
 * customer sees nothing but a released hold on their card.
 *
 * <p>The alternative - charge first and refund on failure - is visible to the
 * customer, takes days to reverse, and is the sort of thing that produces
 * support tickets. It is a genuinely better answer in an interview than "we
 * charge them and hope the insert works".
 */
public interface PaymentGateway {

    /**
     * Reserve funds. Does not move money.
     *
     * @param paymentToken a single-use token the client obtained from the
     *                     provider directly. Never a card number: card data must
     *                     not reach this server, its logs, or its backups.
     * @param amountMinor  amount in paise
     * @param reference    our booking reference, so the charge is traceable from
     *                     the provider's dashboard back to a booking
     * @return the outcome; an authorization id on success
     */
    PaymentResult authorize(String paymentToken, long amountMinor, String reference);

    /** Move the money that was authorized. Called only after the booking is committed. */
    void capture(String authorizationId);

    /**
     * Release an authorization without taking anything.
     *
     * <p>Must not throw. It is called from failure-handling paths, and an
     * exception here would replace a recoverable failure with an unrecoverable
     * one while also leaving the customer's money reserved.
     */
    void voidAuthorization(String authorizationId);

    /**
     * @param authorizationId provider reference, present only when approved
     * @param declineReason   a safe, user-facing reason; never the provider's
     *                        raw message, which can contain internal codes
     */
    record PaymentResult(boolean approved, String authorizationId, String declineReason) {

        public static PaymentResult approved(String authorizationId) {
            return new PaymentResult(true, authorizationId, null);
        }

        public static PaymentResult declined(String reason) {
            return new PaymentResult(false, null, reason);
        }
    }
}
