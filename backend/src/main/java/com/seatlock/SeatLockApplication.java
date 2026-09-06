package com.seatlock;

import com.seatlock.config.HoldProperties;
import com.seatlock.config.SecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * SeatLock - a concurrent seat booking system.
 *
 * <p>The one-line description of what this project is about: <b>when N people
 * click the same seat at the same instant, exactly one of them gets it, and the
 * other N-1 get a clear answer rather than a broken booking.</b>
 *
 * <p>That guarantee is built from three independent layers, and the reason there
 * are three is that no single mechanism is both fast enough for the hot path and
 * durable enough to be the system of record:
 *
 * <ol>
 *   <li>{@link com.seatlock.hold.SeatHoldService} - a Redis hold with a TTL,
 *       acquired by an atomic Lua script. Fast, self-expiring, and where
 *       virtually every conflict is decided.</li>
 *   <li>{@link com.seatlock.domain.EventSeat} - a JPA {@code @Version}
 *       optimistic lock. Catches the conflicts Redis could not, in the window
 *       where Redis has failed over or been restarted.</li>
 *   <li>{@code booking_seats_one_active_per_seat} - a partial unique index in
 *       Postgres. Cannot be bypassed by any code path at all.</li>
 * </ol>
 *
 * <p>Start reading at {@code docs/00-overview.md}.
 */
@SpringBootApplication
@EnableScheduling
// Explicitly registering the @ConfigurationProperties classes rather than
// annotating each with @Component. This way one file lists every externally
// configurable part of the system, and a new one cannot appear without a change
// here that a reviewer will see.
@EnableConfigurationProperties({ HoldProperties.class, SecurityProperties.class })
public class SeatLockApplication {

    public static void main(String[] args) {
        SpringApplication.run(SeatLockApplication.class, args);
    }
}
