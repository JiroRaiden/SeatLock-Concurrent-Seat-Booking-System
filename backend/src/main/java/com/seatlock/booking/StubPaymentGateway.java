package com.seatlock.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * A deterministic fake payment provider.
 *
 * <p>Two magic tokens drive the two outcomes that matter:
 *
 * <ul>
 *   <li>{@code tok_demo_success} - approved</li>
 *   <li>{@code tok_demo_decline} - declined</li>
 * </ul>
 *
 * <p>Anything else is approved, so the demo UI works without a lookup table.
 *
 * <p>Deterministic rather than random on purpose. A stub that fails 5% of the
 * time produces tests that fail 5% of the time, and a flaky test suite is worse
 * than no test suite - people learn to re-run it instead of reading it.
 *
 * <p>Marked {@code @Component} with no profile condition so it is the default
 * everywhere. A real provider implementation would be
 * {@code @Profile("!stub-payments")} and this one {@code @Profile("stub-payments")};
 * the deployment guide notes that this project ships with the stub active and
 * never pretends otherwise.
 */
@Component
public class StubPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(StubPaymentGateway.class);

    private static final String DECLINE_TOKEN = "tok_demo_decline";

    /**
     * Authorizations we have issued and not yet captured or voided.
     *
     * <p>Held so the stub can be asserted against in tests: "the booking failed,
     * therefore the authorization must have been voided" is a property worth
     * testing, and it needs somewhere to look.
     */
    private final Map<String, Long> outstanding = new ConcurrentHashMap<>();

    @Override
    public PaymentResult authorize(String paymentToken, long amountMinor, String reference) {
        if (amountMinor <= 0) {
            // Defensive: a zero or negative charge means a pricing bug upstream.
            // Failing loudly here is far better than silently "succeeding" and
            // issuing a free ticket.
            return PaymentResult.declined("Invalid amount");
        }

        if (DECLINE_TOKEN.equals(paymentToken)) {
            log.debug("Stub gateway declining payment for {}", reference);
            return PaymentResult.declined("Your bank declined the payment.");
        }

        String authorizationId = "auth_" + UUID.randomUUID();
        outstanding.put(authorizationId, amountMinor);
        // The token is never logged. It is a bearer credential for a charge; a
        // log aggregator is not where it belongs.
        log.debug("Stub gateway authorized {} paise for {} as {}", amountMinor, reference, authorizationId);
        return PaymentResult.approved(authorizationId);
    }

    @Override
    public void capture(String authorizationId) {
        Long amount = outstanding.remove(authorizationId);
        if (amount == null) {
            // Capturing twice, or capturing something already voided. In a real
            // gateway this is an error; here we log rather than throw, because
            // capture runs after the booking is already committed and throwing
            // would turn a bookkeeping problem into a failed response for a
            // booking the customer has actually got.
            log.warn("Capture for unknown or already-settled authorization {}", authorizationId);
            return;
        }
        log.debug("Stub gateway captured {} paise for {}", amount, authorizationId);
    }

    @Override
    public void voidAuthorization(String authorizationId) {
        // Must never throw: it runs from catch blocks. See the interface docs.
        try {
            Long amount = outstanding.remove(authorizationId);
            if (amount != null) {
                log.debug("Stub gateway voided authorization {}", authorizationId);
            }
        } catch (RuntimeException ex) {
            log.error("Failed to void authorization {}", authorizationId, ex);
        }
    }

    /** Test hook: is this authorization still reserved (neither captured nor voided)? */
    public boolean isOutstanding(String authorizationId) {
        return outstanding.containsKey(authorizationId);
    }
}
