package com.seatlock.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Settings that govern how long a seat stays reserved.
 *
 * <p>These are bound from {@code seatlock.hold.*} and <b>validated at startup</b>.
 * That is the reason this class exists instead of a handful of
 * {@code @Value("${seatlock.hold.ttl}")} fields scattered through the services:
 *
 * <ul>
 *   <li>a typo in the property name fails the build of the context, loudly, at
 *       boot - not at 9pm when the first user clicks a seat;</li>
 *   <li>{@code Duration} parsing means the config file says {@code 8m} rather
 *       than {@code 480000}, and nobody has to remember the unit;</li>
 *   <li>the constraints below turn "someone set the TTL to zero" into a startup
 *       failure rather than a system where every hold expires instantly.</li>
 * </ul>
 */
@Validated
@ConfigurationProperties(prefix = "seatlock.hold")
public class HoldProperties {

    /**
     * How long seats stay reserved after the user presses Proceed.
     *
     * <p>The most consequential number in the product. Too short and users lose
     * their seats mid-payment, which is infuriating and generates support load.
     * Too long and abandoned carts starve real buyers during a hot release - at
     * a 90% abandonment rate, a 20-minute hold on a 200-seat screen can make a
     * show look sold out while barely a quarter of it is actually sold.
     *
     * <p>Eight minutes is chosen to sit just above the observed time to complete
     * a card payment including an OTP round trip.
     */
    @NotNull
    private Duration ttl = Duration.ofMinutes(8);

    /** One-off grace period a user can claim while mid-payment. */
    @NotNull
    private Duration extension = Duration.ofMinutes(3);

    /**
     * Hard cap on seats per booking.
     *
     * <p>Two jobs. It is a business rule (nobody buys 40 seats through the
     * consumer flow), and it is a control: without it, one request could hold an
     * entire auditorium, and the Lua script's key list would be unbounded, which
     * would block Redis's single command thread for as long as it took to walk.
     */
    @Min(1)
    @Max(50)
    private int maxSeatsPerBooking = 10;

    public Duration getTtl() { return ttl; }
    public void setTtl(Duration ttl) { this.ttl = ttl; }

    public Duration getExtension() { return extension; }
    public void setExtension(Duration extension) { this.extension = extension; }

    public int getMaxSeatsPerBooking() { return maxSeatsPerBooking; }
    public void setMaxSeatsPerBooking(int maxSeatsPerBooking) {
        this.maxSeatsPerBooking = maxSeatsPerBooking;
    }
}
