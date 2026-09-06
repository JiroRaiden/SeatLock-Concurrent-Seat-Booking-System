-- =============================================================================
-- V1: initial schema
--
-- Flyway runs this exactly once per database and records a checksum. If anyone
-- edits this file after it has run somewhere, Flyway refuses to start. That is
-- the point: migrations are append-only history, not editable documents.
--
-- Conventions used throughout, and why:
--
--   TIMESTAMPTZ, never TIMESTAMP
--       TIMESTAMP has no time zone attached, so "2026-09-04 19:30:00" means
--       something different depending on who reads it. A ticketing system spans
--       cities; an event that starts at 19:30 IST must not become 19:30 UTC.
--       TIMESTAMPTZ stores an absolute instant and converts on display.
--
--   BIGINT minor units for money, never FLOAT/DOUBLE
--       0.1 + 0.2 != 0.3 in binary floating point. Ticket prices are stored in
--       paise (1/100 of a rupee) as whole numbers, so arithmetic is exact.
--       NUMERIC would also be exact; integers are chosen because they are
--       cheaper to index and map cleanly to a Java long.
--
--   Explicit ON DELETE behaviour on every foreign key
--       Leaving it to the default (NO ACTION) is fine, but stating it makes the
--       intent readable: RESTRICT means "this row is referenced, refuse the
--       delete"; CASCADE means "this child has no meaning without its parent".
-- =============================================================================


-- -----------------------------------------------------------------------------
-- users
-- -----------------------------------------------------------------------------
CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    email           VARCHAR(320) NOT NULL,
    -- BCrypt output is always 60 characters. We size to 100 to leave room for a
    -- future migration to Argon2id without another DDL change.
    password_hash   VARCHAR(100) NOT NULL,
    display_name    VARCHAR(100) NOT NULL,
    role            VARCHAR(20)  NOT NULL DEFAULT 'ROLE_USER',
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    -- Roles are a closed set. A CHECK constraint means a bug that writes
    -- 'ROLE_SUPERADMIN' fails at the database, not at some future authorization
    -- check that happens to string-match. Defence in depth applies to data too.
    CONSTRAINT users_role_valid CHECK (role IN ('ROLE_USER', 'ROLE_ADMIN'))
);

-- Email uniqueness is enforced case-insensitively. Without LOWER(), 'A@b.com'
-- and 'a@b.com' are two accounts, which is both a UX bug and an account-takeover
-- vector (register the uppercase variant of someone's address, then rely on a
-- downstream system that lowercases).
CREATE UNIQUE INDEX users_email_lower_key ON users (LOWER(email));


-- -----------------------------------------------------------------------------
-- refresh_tokens
--
-- Why store refresh tokens at all, when the whole point of JWT is to be
-- stateless? Because "stateless" and "revocable" are opposites. A stolen access
-- token is survivable: it expires in 15 minutes. A stolen refresh token that
-- lives 7 days and cannot be revoked is not. So access tokens stay stateless
-- and cheap; refresh tokens are stateful and revocable. That is the trade.
--
-- We store a SHA-256 hash, never the token. If this table leaks, the attacker
-- gets hashes they cannot present to the API.
-- -----------------------------------------------------------------------------
CREATE TABLE refresh_tokens (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash   CHAR(64)    NOT NULL UNIQUE,   -- hex-encoded SHA-256
    issued_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at   TIMESTAMPTZ NOT NULL,
    revoked_at   TIMESTAMPTZ,
    -- When a refresh token is used, we issue a new one and point the old row at
    -- it. If a token that was already rotated is presented again, that is a
    -- replay: we revoke the whole chain. This is "refresh token rotation with
    -- reuse detection", the standard defence against stolen refresh tokens.
    replaced_by  BIGINT      REFERENCES refresh_tokens (id) ON DELETE SET NULL
);

CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id) WHERE revoked_at IS NULL;


-- -----------------------------------------------------------------------------
-- venues and their physical seats
--
-- A seat is a physical object bolted to a floor. It exists whether or not
-- anything is showing tonight. This is why seats belong to a venue, not to an
-- event - a distinction that a lot of naive schemas get wrong and then have to
-- duplicate the entire seat map per showing by hand.
-- -----------------------------------------------------------------------------
CREATE TABLE venues (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(150) NOT NULL,
    city        VARCHAR(100) NOT NULL,
    address     VARCHAR(400) NOT NULL,
    -- Total seats is derived, but caching it avoids a COUNT(*) on every list
    -- page. It is written once when the seat map is created.
    seat_count  INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX venues_city_idx ON venues (city);

CREATE TABLE seats (
    id           BIGSERIAL PRIMARY KEY,
    venue_id     BIGINT      NOT NULL REFERENCES venues (id) ON DELETE CASCADE,
    section      VARCHAR(40) NOT NULL,   -- 'RECLINER', 'PRIME', 'CLASSIC'
    row_label    VARCHAR(4)  NOT NULL,   -- 'A' .. 'Z', 'AA' ...
    seat_number  SMALLINT    NOT NULL,   -- 1-based, left to right

    -- row_index / col_index are the rendering coordinates. Storing them means
    -- the frontend never has to parse 'A' into a row position, and a venue with
    -- an irregular layout (aisle gaps, a wheelchair bay) can place seats exactly
    -- where they physically are rather than on a naive grid.
    row_index    SMALLINT    NOT NULL,
    col_index    SMALLINT    NOT NULL,

    CONSTRAINT seats_number_positive CHECK (seat_number > 0),
    -- One seat per (venue, row, number). Prevents a duplicate seat map import
    -- from silently doubling a venue's capacity.
    CONSTRAINT seats_unique_position UNIQUE (venue_id, row_label, seat_number)
);

CREATE INDEX seats_venue_idx ON seats (venue_id);


-- -----------------------------------------------------------------------------
-- events (a showing) and its price tiers
-- -----------------------------------------------------------------------------
CREATE TABLE events (
    id           BIGSERIAL PRIMARY KEY,
    venue_id     BIGINT       NOT NULL REFERENCES venues (id) ON DELETE RESTRICT,
    title        VARCHAR(200) NOT NULL,
    subtitle     VARCHAR(200),
    description  TEXT,
    category     VARCHAR(40)  NOT NULL,   -- 'MOVIE', 'CONCERT', 'COMEDY', 'SPORTS'
    language     VARCHAR(40),
    -- Certification / age rating, e.g. 'UA13+'. Free text on purpose; this is
    -- display metadata, not something we make decisions on.
    certification VARCHAR(16),
    duration_minutes SMALLINT,
    poster_url   VARCHAR(500),
    backdrop_url VARCHAR(500),
    starts_at    TIMESTAMPTZ  NOT NULL,
    -- Selling stops here. Defaults to starts_at in the seed data, but a venue
    -- may want to keep the counter open for 10 minutes after the trailers roll.
    sales_close_at TIMESTAMPTZ NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'PUBLISHED',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT events_status_valid CHECK (status IN ('DRAFT', 'PUBLISHED', 'CANCELLED')),
    -- Category is a closed set mirrored by the EventCategory enum in Java. Both
    -- sides declare it so neither can drift silently: if someone adds a value to
    -- the enum without a migration, inserts fail loudly at the database.
    CONSTRAINT events_category_valid
        CHECK (category IN ('MOVIE', 'CONCERT', 'COMEDY', 'SPORTS', 'THEATRE')),
    CONSTRAINT events_sales_before_start CHECK (sales_close_at <= starts_at + INTERVAL '30 minutes')
);

-- The listing page filters on status and orders by start time. A composite
-- index in that exact order lets Postgres satisfy the WHERE and the ORDER BY
-- from one index scan, with no sort step.
CREATE INDEX events_status_starts_idx ON events (status, starts_at);
CREATE INDEX events_venue_idx ON events (venue_id);

CREATE TABLE price_tiers (
    id          BIGSERIAL PRIMARY KEY,
    event_id    BIGINT      NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    name        VARCHAR(40) NOT NULL,     -- 'Recliner', 'Prime', 'Classic'
    -- Money in paise. 45000 = Rs 450.00
    price_minor BIGINT      NOT NULL,
    -- Hex colour the seat map uses for this tier's legend and seat fill.
    colour      VARCHAR(9)  NOT NULL DEFAULT '#4B5563',
    sort_order  SMALLINT    NOT NULL DEFAULT 0,

    CONSTRAINT price_tiers_price_non_negative CHECK (price_minor >= 0),
    CONSTRAINT price_tiers_unique_name UNIQUE (event_id, name)
);


-- -----------------------------------------------------------------------------
-- event_seats  <-- the bookable unit, and the heart of the concurrency story
--
-- One row per (event, seat). Seat A5 at PVR Forum is ONE row in `seats` but a
-- DIFFERENT row in `event_seats` for tonight's 7pm show and tomorrow's 10am
-- show. Booking one must not affect the other.
--
-- Two columns here do the heavy lifting:
--
--   status   - AVAILABLE / BOOKED / BLOCKED.
--              Note what is NOT in this list: HELD. A temporary hold lives in
--              Redis with a TTL, never in Postgres. Writing "HELD" to a row
--              would mean every seat click is a database write, and every
--              abandoned cart needs a cleanup job to un-write it. See
--              docs/03-concurrency.md.
--
--   version  - the optimistic lock. Hibernate adds "AND version = ?" to every
--              UPDATE and bumps the value. Two transactions that read version 3
--              and both try to write will produce one UPDATE that matches 1 row
--              and one that matches 0 rows; the second throws
--              OptimisticLockException. No locks are ever held while waiting.
-- -----------------------------------------------------------------------------
CREATE TABLE event_seats (
    id            BIGSERIAL PRIMARY KEY,
    event_id      BIGINT      NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    seat_id       BIGINT      NOT NULL REFERENCES seats (id) ON DELETE RESTRICT,
    price_tier_id BIGINT      NOT NULL REFERENCES price_tiers (id) ON DELETE RESTRICT,
    status        VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    version       BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT event_seats_status_valid CHECK (status IN ('AVAILABLE', 'BOOKED', 'BLOCKED')),
    -- Guarantee #1: a seat cannot appear twice in the same event. If it could,
    -- two "different" seats would represent one physical chair and both could
    -- be sold.
    CONSTRAINT event_seats_unique_per_event UNIQUE (event_id, seat_id)
);

-- The seat-map endpoint fetches every seat for one event. This index makes that
-- a single index scan. INCLUDE carries status and version in the index leaf so
-- Postgres can answer without touching the heap (an index-only scan).
CREATE INDEX event_seats_event_idx ON event_seats (event_id) INCLUDE (status, version);
-- "How many seats are left?" runs on every event card. A partial index that
-- only contains AVAILABLE rows is small and stays hot in cache.
CREATE INDEX event_seats_available_idx ON event_seats (event_id) WHERE status = 'AVAILABLE';


-- -----------------------------------------------------------------------------
-- bookings
-- -----------------------------------------------------------------------------
CREATE TABLE bookings (
    id            BIGSERIAL PRIMARY KEY,

    -- The id the outside world sees. Sequential integers are enumerable: if
    -- your booking is /bookings/1837, someone will try /bookings/1836. We do
    -- have an ownership check on that endpoint, but a random public identifier
    -- means an attacker cannot even measure how many bookings exist.
    public_id     UUID        NOT NULL DEFAULT gen_random_uuid(),
    -- Short human-quotable code printed on the ticket, e.g. 'SL-7QK4M2'.
    reference     VARCHAR(16) NOT NULL,

    user_id       BIGINT      NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    event_id      BIGINT      NOT NULL REFERENCES events (id) ON DELETE RESTRICT,
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    total_minor   BIGINT      NOT NULL,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- When the underlying Redis hold expires. Stored so the UI can render a
    -- countdown after a page refresh, and so the reaper knows what to sweep.
    expires_at    TIMESTAMPTZ,
    confirmed_at  TIMESTAMPTZ,
    cancelled_at  TIMESTAMPTZ,
    version       BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT bookings_status_valid
        CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT bookings_total_non_negative CHECK (total_minor >= 0),
    CONSTRAINT bookings_public_id_key UNIQUE (public_id),
    CONSTRAINT bookings_reference_key UNIQUE (reference),
    -- A confirmed booking must have a confirmation time. Constraints like this
    -- stop "impossible" rows from ever existing, which means downstream code
    -- never has to defend against them.
    CONSTRAINT bookings_confirmed_has_timestamp
        CHECK (status <> 'CONFIRMED' OR confirmed_at IS NOT NULL)
);

-- "My bookings", newest first.
CREATE INDEX bookings_user_created_idx ON bookings (user_id, created_at DESC);
-- The reaper's query: find PENDING bookings past their expiry.
CREATE INDEX bookings_pending_expiry_idx ON bookings (expires_at) WHERE status = 'PENDING';


-- -----------------------------------------------------------------------------
-- booking_seats  <-- Guarantee #3, the absolute floor
--
-- Even if every line of Java in this project were deleted and replaced with
-- something broken, the database still cannot record two live bookings for one
-- seat. That is what the partial unique index below buys us.
--
-- Why `active` rather than joining to bookings.status? Because a UNIQUE INDEX
-- can only see columns of its own table. A partial index needs its predicate
-- locally. So `active` is a deliberate, tiny denormalisation of "this booking
-- is PENDING or CONFIRMED", maintained in the same transaction that changes the
-- booking's status. The trigger below makes that maintenance impossible to
-- forget.
-- -----------------------------------------------------------------------------
CREATE TABLE booking_seats (
    id             BIGSERIAL PRIMARY KEY,
    booking_id     BIGINT  NOT NULL REFERENCES bookings (id) ON DELETE CASCADE,
    event_seat_id  BIGINT  NOT NULL REFERENCES event_seats (id) ON DELETE RESTRICT,
    -- Price is copied, not looked up. If the venue changes tier pricing next
    -- week, an already-issued ticket must still show what the customer paid.
    price_minor    BIGINT  NOT NULL,
    active         BOOLEAN NOT NULL DEFAULT TRUE,

    CONSTRAINT booking_seats_price_non_negative CHECK (price_minor >= 0),
    -- A seat appears at most once within a single booking.
    CONSTRAINT booking_seats_unique_in_booking UNIQUE (booking_id, event_seat_id)
);

-- ***** THE OVERSELL GUARANTEE *****
-- At most one ACTIVE booking_seats row per event_seat, enforced by Postgres.
-- Cancelled and expired bookings set active = FALSE, which drops their rows out
-- of this index and frees the seat for resale.
CREATE UNIQUE INDEX booking_seats_one_active_per_seat
    ON booking_seats (event_seat_id) WHERE active;

CREATE INDEX booking_seats_booking_idx ON booking_seats (booking_id);


-- -----------------------------------------------------------------------------
-- Keep booking_seats.active in step with bookings.status, in the database.
--
-- This could live in Java. It lives here because it is an invariant, not a
-- business rule: there is no correct system state in which a CANCELLED booking
-- still holds an active seat claim. Invariants belong as close to the data as
-- possible, where no code path - including a manual UPDATE run by a panicking
-- engineer at 2am - can bypass them.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION sync_booking_seat_active() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.status IN ('CANCELLED', 'EXPIRED') AND OLD.status NOT IN ('CANCELLED', 'EXPIRED') THEN
        UPDATE booking_seats SET active = FALSE WHERE booking_id = NEW.id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER bookings_status_sync_seats
    AFTER UPDATE OF status ON bookings
    FOR EACH ROW
    WHEN (OLD.status IS DISTINCT FROM NEW.status)
    EXECUTE FUNCTION sync_booking_seat_active();


-- -----------------------------------------------------------------------------
-- idempotency_keys
--
-- The problem this solves: a user taps "Pay" on a flaky mobile connection. The
-- request reaches us, we create the booking, and the response is lost. The
-- phone retries. Without this table the user gets two bookings and two charges.
--
-- The client sends an Idempotency-Key header. The first request stores the key
-- with its result; a retry with the same key replays the stored response
-- instead of executing again. The UNIQUE constraint on (user_id, idem_key) is
-- what makes this safe under concurrency - two simultaneous retries race to
-- INSERT, exactly one wins, the loser waits and replays.
--
-- request_hash guards against a client reusing a key for a different payload,
-- which would otherwise let them retrieve someone else's stored response shape.
-- -----------------------------------------------------------------------------
CREATE TABLE idempotency_keys (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    idem_key        VARCHAR(120) NOT NULL,
    endpoint        VARCHAR(120) NOT NULL,
    request_hash    CHAR(64)     NOT NULL,      -- SHA-256 of the canonical request body
    response_status SMALLINT,
    response_body   TEXT,
    booking_id      BIGINT       REFERENCES bookings (id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT idempotency_unique_per_user UNIQUE (user_id, idem_key)
);

-- Keys are only useful for a short window; this index drives the cleanup job.
CREATE INDEX idempotency_created_idx ON idempotency_keys (created_at);
