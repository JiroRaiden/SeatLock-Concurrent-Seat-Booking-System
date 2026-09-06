package com.seatlock.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds test data with plain SQL.
 *
 * <h2>Why SQL and not the repositories</h2>
 *
 * The domain entities have protected no-arg constructors and no public setters
 * for venue/seat/event fields, because nothing in the <em>application</em> ever
 * creates a venue or a seat map - those arrive through migrations or an admin
 * import. Adding public constructors purely so tests can call them would weaken
 * the production API to suit the test suite, which is the wrong way round.
 *
 * <p>Writing fixtures in SQL keeps the entities strict and has a second benefit:
 * the fixture exercises the real schema, including its constraints. If a test
 * fixture violates a CHECK constraint, that is worth knowing.
 */
@Component
public class TestFixtures {

    private final JdbcTemplate jdbc;

    public TestFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** An event and the ids of every bookable seat in it. */
    public record Auditorium(long venueId, long eventId, List<Long> eventSeatIds) {
        public long firstSeat() { return eventSeatIds.get(0); }
    }

    /**
     * Wipe every table between tests.
     *
     * <p>{@code TRUNCATE ... RESTART IDENTITY CASCADE} rather than DELETE:
     * it is far faster, it resets the sequences so ids are predictable, and
     * CASCADE follows the foreign keys so the order of the table list does not
     * matter. Resetting identities matters more than it looks - a test that
     * accidentally depends on "the event with id 1" passes alone and fails in a
     * suite without it.
     */
    public void reset() {
        jdbc.execute("""
            TRUNCATE TABLE booking_seats, bookings, idempotency_keys, refresh_tokens,
                           event_seats, price_tiers, events, seats, venues, users
            RESTART IDENTITY CASCADE
            """);
    }

    /** The plaintext behind {@link #FIXTURE_PASSWORD_HASH}, for tests that log in. */
    public static final String FIXTURE_PASSWORD = "TestPassword123!";

    /**
     * A genuine cost-4 BCrypt hash of {@link #FIXTURE_PASSWORD}.
     *
     * <p>Cost 4 rather than the production 12: a test that creates fifty users
     * would otherwise spend twelve seconds on key derivation it does not care
     * about. Tests that actually exercise password <em>strength</em> go through
     * {@code /auth/register} and get the real cost-12 encoder.
     *
     * <p>It has to be a real hash rather than a plausible-looking string.
     * {@code BCryptPasswordEncoder.matches} runs the KDF and compares, so a
     * hand-written 60-character lookalike verifies against nothing and any test
     * that tried to log in as a fixture user would fail with a 401 that looks
     * like a bug in the login code.
     */
    public static final String FIXTURE_PASSWORD_HASH =
            "$2a$04$WRJ8A0SJm/7CwQ3MShhEhuT2cJhZhpZlDCpdXrpfb4uc/tnjuYZAq";

    /** Insert a user directly, with a pre-computed BCrypt hash. */
    public long createUser(String email, String role) {
        jdbc.update("""
                INSERT INTO users (email, password_hash, display_name, role)
                VALUES (?, ?, ?, ?)
                """, email, FIXTURE_PASSWORD_HASH, "Test " + email, role);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    public long createUser(String email) {
        return createUser(email, "ROLE_USER");
    }

    /**
     * Create a venue, a seat map, a published event, its price tiers and its
     * {@code event_seats} - everything needed to book something.
     *
     * @param rows         number of rows, labelled A, B, C...
     * @param seatsPerRow  seats in each row
     */
    public Auditorium createAuditorium(int rows, int seatsPerRow) {
        jdbc.update("INSERT INTO venues (name, city, address) VALUES (?, ?, ?)",
                "Test Venue " + System.nanoTime(), "Kolkata", "1 Test Street");
        long venueId = jdbc.queryForObject("SELECT MAX(id) FROM venues", Long.class);

        for (int r = 0; r < rows; r++) {
            String label = String.valueOf((char) ('A' + r));
            for (int s = 1; s <= seatsPerRow; s++) {
                jdbc.update("""
                        INSERT INTO seats (venue_id, section, row_label, seat_number, row_index, col_index)
                        VALUES (?, 'PRIME', ?, ?, ?, ?)
                        """, venueId, label, s, r + 1, s);
            }
        }
        jdbc.update("UPDATE venues SET seat_count = ? WHERE id = ?", rows * seatsPerRow, venueId);

        jdbc.update("""
                INSERT INTO events (venue_id, title, category, starts_at, sales_close_at, status)
                VALUES (?, 'Test Show', 'MOVIE', NOW() + INTERVAL '2 hours', NOW() + INTERVAL '2 hours', 'PUBLISHED')
                """, venueId);
        long eventId = jdbc.queryForObject("SELECT MAX(id) FROM events", Long.class);

        jdbc.update("""
                INSERT INTO price_tiers (event_id, name, price_minor, colour, sort_order)
                VALUES (?, 'PRIME', 45000, '#5EC8A0', 0)
                """, eventId);
        long tierId = jdbc.queryForObject("SELECT MAX(id) FROM price_tiers", Long.class);

        jdbc.update("""
                INSERT INTO event_seats (event_id, seat_id, price_tier_id, status)
                SELECT ?, s.id, ?, 'AVAILABLE' FROM seats s WHERE s.venue_id = ?
                """, eventId, tierId, venueId);

        List<Long> seatIds = jdbc.queryForList("""
                SELECT es.id FROM event_seats es
                  JOIN seats s ON s.id = es.seat_id
                 WHERE es.event_id = ?
                 ORDER BY s.row_index, s.seat_number
                """, Long.class, eventId);

        return new Auditorium(venueId, eventId, seatIds);
    }

    /** Close sales, to test the SALES_CLOSED path. */
    public void closeSales(long eventId) {
        jdbc.update("UPDATE events SET sales_close_at = NOW() - INTERVAL '1 minute' WHERE id = ?", eventId);
    }

    /** How many live claims exist on a seat. The oversell assertion. */
    public int activeClaimsOn(long eventSeatId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM booking_seats WHERE event_seat_id = ? AND active", Integer.class, eventSeatId);
        return count == null ? 0 : count;
    }

    public String seatStatus(long eventSeatId) {
        return jdbc.queryForObject("SELECT status FROM event_seats WHERE id = ?", String.class, eventSeatId);
    }

    public int countBookingsWithStatus(String status) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE status = ?", Integer.class, status);
        return count == null ? 0 : count;
    }

    /** Force a pending booking to look lapsed, without waiting eight minutes. */
    public void backdateHoldExpiry(long bookingId) {
        jdbc.update("UPDATE bookings SET expires_at = NOW() - INTERVAL '1 minute' WHERE id = ?", bookingId);
    }
}
