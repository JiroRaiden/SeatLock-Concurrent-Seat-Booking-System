-- =============================================================================
-- V900: demo data - DEV PROFILE ONLY.
--
-- This file lives in `db/demo`, a location that is only added to Flyway's search
-- path by application-dev.yml. Production's Flyway only scans `db/migration`, so
-- these rows can never reach a real database even by accident.
--
-- Version 900 is chosen far above the real migrations so that adding V2, V3...
-- later never collides with it.
--
-- Credentials created here (dev only, and the passwords are in the README):
--     user@seatlock.dev  / Password123!
--     admin@seatlock.dev / Admin123!
-- =============================================================================

INSERT INTO users (email, password_hash, display_name, role) VALUES
    ('user@seatlock.dev',  '$2b$10$kviFutndNg5QqmmrCO0KCeGW69Hh1BPq9nsY3MhhbNsrlU/B.Td6.', 'Demo User',  'ROLE_USER'),
    ('admin@seatlock.dev', '$2b$10$xhfv6MX6gcXcS9pKOCMY3uPfY2JgK2iRIzt/0o9O4DrY2tUb3.5bu', 'Demo Admin', 'ROLE_ADMIN');


-- -----------------------------------------------------------------------------
-- Venues
-- -----------------------------------------------------------------------------
INSERT INTO venues (name, city, address) VALUES
    ('Aurora IMAX, Park Street',    'Kolkata',   '18 Park Street, Kolkata 700016'),
    ('Meridian Cinemas, Salt Lake', 'Kolkata',   'Sector V, Bidhannagar, Kolkata 700091'),
    ('Nova Arena',                  'Bengaluru', 'Outer Ring Road, Bellandur, Bengaluru 560103');


-- -----------------------------------------------------------------------------
-- Seat maps
--
-- Generated with generate_series rather than 600 literal INSERT statements.
-- Layout of a screen, front (nearest the screen) to back:
--
--     rows J..N   CLASSIC   18 seats   cheapest, closest to the screen
--     rows C..H   PRIME     16 seats   the sweet spot
--     rows A..B   RECLINER   8 seats   back row, widest seats, priciest
--
-- Note there is no row I. Cinemas skip it because "I" and "1" are confusing on
-- a printed ticket - a small realism detail that is also a nice reminder that
-- row labels are display strings, while row_index is the thing you sort by.
--
-- col_index inserts a 1-unit gap in the middle of each row so the frontend can
-- draw a centre aisle without hardcoding where it goes.
-- -----------------------------------------------------------------------------
INSERT INTO seats (venue_id, section, row_label, seat_number, row_index, col_index)
SELECT
    v.id,
    layout.section,
    layout.row_label,
    n                                              AS seat_number,
    layout.row_index,
    -- Shift everything past the halfway point one column right, creating the aisle.
    n + CASE WHEN n > layout.width / 2 THEN 1 ELSE 0 END AS col_index
FROM venues v
CROSS JOIN (
    VALUES
        ('RECLINER', 'A',  1,  8),
        ('RECLINER', 'B',  2,  8),
        ('PRIME',    'C',  3, 16),
        ('PRIME',    'D',  4, 16),
        ('PRIME',    'E',  5, 16),
        ('PRIME',    'F',  6, 16),
        ('PRIME',    'G',  7, 16),
        ('PRIME',    'H',  8, 16),
        ('CLASSIC',  'J',  9, 18),
        ('CLASSIC',  'K', 10, 18),
        ('CLASSIC',  'L', 11, 18),
        ('CLASSIC',  'M', 12, 18),
        ('CLASSIC',  'N', 13, 18)
) AS layout(section, row_label, row_index, width)
CROSS JOIN LATERAL generate_series(1, layout.width) AS n;

UPDATE venues v SET seat_count = (SELECT COUNT(*) FROM seats s WHERE s.venue_id = v.id);


-- -----------------------------------------------------------------------------
-- Events
--
-- starts_at is relative to NOW() so the demo data never goes stale. Whenever you
-- reset your database the shows are always "this week".
-- -----------------------------------------------------------------------------
INSERT INTO events (venue_id, title, subtitle, description, category, language,
                    certification, duration_minutes, poster_url, backdrop_url,
                    starts_at, sales_close_at, status)
SELECT
    v.id, e.title, e.subtitle, e.description, e.category, e.language,
    e.certification, e.duration, NULL, NULL,
    NOW() + e.offset_hours * INTERVAL '1 hour',
    NOW() + e.offset_hours * INTERVAL '1 hour',
    'PUBLISHED'
FROM (
    VALUES
        ('Aurora IMAX, Park Street',    'Meridian',            'The last signal from Europa',
         'A deep-space salvage crew answers a distress call that predates human spaceflight. Shot entirely on 70mm.',
         'MOVIE',   'English',         'UA13+', 148,  6),
        ('Aurora IMAX, Park Street',    'Meridian',            'The last signal from Europa',
         'A deep-space salvage crew answers a distress call that predates human spaceflight. Shot entirely on 70mm.',
         'MOVIE',   'English',         'UA13+', 148, 30),
        ('Aurora IMAX, Park Street',    'Kanchenjunga Diaries','A road film across the eastern Himalaya',
         'Two estranged sisters drive from Siliguri to Pelling with their mother''s ashes and a broken cassette deck.',
         'MOVIE',   'Bengali',         'U',     127, 27),
        ('Meridian Cinemas, Salt Lake', 'Static Bloom',        'Neo-noir, and very loud',
         'A sound engineer in 1997 Bombay hears a murder in the background of a tape she is mastering.',
         'MOVIE',   'Hindi',           'A',     134, 52),
        ('Meridian Cinemas, Salt Lake', 'Aarohi Sen Live',     'The Long Way Home tour',
         'Two hours, one grand piano, no setlist. Aarohi takes requests from the floor for the entire second half.',
         'CONCERT', 'Bengali/English',  'U',     120, 76),
        ('Nova Arena',                  'Standup: Room Tone',  'Prateek Rao, brand new hour',
         'Ninety minutes of new material about landlords, laundry, and the exact moment you become your father.',
         'COMEDY',  'Hindi/English',   'A',      90,  8)
) AS e(venue_name, title, subtitle, description, category, language, certification, duration, offset_hours)
JOIN venues v ON v.name = e.venue_name;


-- -----------------------------------------------------------------------------
-- Price tiers, one set per event.
--
-- Prices are in paise: 45000 = Rs 450.00
-- The colour column feeds the seat-map legend directly, so pricing and its
-- visual encoding stay in one place instead of drifting apart in CSS.
-- -----------------------------------------------------------------------------
INSERT INTO price_tiers (event_id, name, price_minor, colour, sort_order)
SELECT ev.id, t.name, t.price, t.colour, t.sort_order
FROM events ev
CROSS JOIN (
    VALUES
        ('RECLINER', 65000, '#F5C451', 0),
        ('PRIME',    45000, '#5EC8A0', 1),
        ('CLASSIC',  28000, '#7DA2F0', 2)
) AS t(name, price, colour, sort_order);


-- -----------------------------------------------------------------------------
-- event_seats: the cross product of (event) x (its venue's seats), joined to the
-- matching price tier by name. One statement produces the entire bookable
-- inventory - roughly 200 seats x 6 events.
-- -----------------------------------------------------------------------------
INSERT INTO event_seats (event_id, seat_id, price_tier_id, status)
SELECT ev.id, s.id, pt.id, 'AVAILABLE'
FROM events ev
JOIN seats s          ON s.venue_id = ev.venue_id
JOIN price_tiers pt   ON pt.event_id = ev.id AND pt.name = s.section;


-- -----------------------------------------------------------------------------
-- Make the demo look lived-in: mark a scattered ~18% of seats BOOKED so the seat
-- map is not a pristine grid of green. Deterministic (seeded) so screenshots are
-- reproducible.
--
-- BLOCKED is used for a handful of seats to demonstrate the third status: seats
-- the venue has taken out of sale (broken recliner, camera position, house
-- seats). They render differently from sold seats.
-- -----------------------------------------------------------------------------
SELECT setseed(0.42);

UPDATE event_seats es
SET status = 'BOOKED'
WHERE es.id IN (
    SELECT id FROM event_seats ORDER BY md5(id::text) LIMIT (SELECT COUNT(*) * 18 / 100 FROM event_seats)
);

UPDATE event_seats es
SET status = 'BLOCKED'
FROM seats s
WHERE es.seat_id = s.id
  AND s.row_label = 'H'
  AND s.seat_number IN (8, 9)
  AND es.status = 'AVAILABLE';
