# Data model

Everything here comes from `backend/src/main/resources/db/migration/V1__initial_schema.sql`
and the entity classes in `com.seatlock.domain`. The migration is the single
source of truth: Flyway runs it once per database and records a checksum, so
editing it after it has run anywhere makes the application refuse to start.
Migrations are append-only history, not editable documents.

Three conventions run through the whole schema, and they are worth stating once:

- **`TIMESTAMPTZ`, never `TIMESTAMP`.** A bare `TIMESTAMP` stores "2026-09-04
  19:30:00" with no zone attached, so it means something different depending on
  who reads it. A ticketing system spans cities; a show that starts at 19:30 IST
  must not become 19:30 UTC because a server in a different region wrote it.
  `TIMESTAMPTZ` stores an absolute instant and converts on display, which is
  also why every entity field is a `java.time.Instant` and never a
  `LocalDateTime`.
- **Money is `BIGINT` in minor units (paise), never `FLOAT` or `DOUBLE`.**
  Binary floating point cannot represent 0.1 exactly; summing six ticket prices
  as doubles can produce `2699.9999999999995`, and a customer sees a stray paisa
  on their receipt. Storing `45000` for ₹450.00 makes every total exact by
  construction. `NUMERIC` would also be exact — integers are chosen because they
  are cheaper to index and map cleanly onto a Java `long`. Every DTO field
  carrying money ends in `Minor` so it cannot be mistaken for rupees.
- **Explicit `ON DELETE` on every foreign key.** The default (`NO ACTION`) would
  behave the same as `RESTRICT` here, but writing it out makes the intent
  readable: `RESTRICT` means "this row is referenced, refuse the delete";
  `CASCADE` means "this child has no meaning without its parent".

---

## Entity relationships

```
                +-----------+
                |  venues   |  a building. Exists whether or not
                +-----+-----+  anything is showing tonight.
                      |
        +-------------+--------------+
        | 1:N                        | 1:N
        v                            v
   +---------+                 +-----------+          +--------------+
   |  seats  |                 |  events   |--- 1:N ->| price_tiers  |
   +----+----+                 +-----+-----+          +------+-------+
        |  a physical chair          |  a showing            |
        |                            |                       |
        |          +-----------------+                       |
        |          |                                         |
        |  N:1     |  N:1                              N:1   |
        +------> +-+-------------+ <-------------------------+
                 | event_seats   |   THE BOOKABLE UNIT
                 |               |   status: AVAILABLE|BOOKED|BLOCKED
                 |               |   version: the optimistic lock
                 +------+--------+
                        ^
                        | N:1     UNIQUE (event_seat_id) WHERE active
                        |         <-- the oversell guarantee
                 +------+--------+
                 | booking_seats |
                 +------+--------+
                        | N:1
                        v
   +--------+     +-----------+
   | users  |<----| bookings  |  PENDING | CONFIRMED | CANCELLED | EXPIRED
   +---+----+ 1:N +-----------+
       |
       | 1:N                 1:N
       +--> refresh_tokens   +--> idempotency_keys
            (self-referencing     (may reference a booking)
             replaced_by chain)
```

---

## Table by table

### `users`

Account records. `email VARCHAR(320)` (the RFC maximum), `password_hash
VARCHAR(100)`, `display_name`, `role`, `enabled`, timestamps.

Interesting bits:

- `password_hash` is sized 100 even though BCrypt output is always exactly 60
  characters. The headroom is deliberate: swapping to Argon2id later produces a
  longer string, and the column already fits it, so that migration needs no DDL
  change.
- `CONSTRAINT users_role_valid CHECK (role IN ('ROLE_USER', 'ROLE_ADMIN'))`.
  Roles are a closed set. A bug that writes `'ROLE_SUPERADMIN'` fails at the
  database rather than at some future authorisation check that happens to
  string-match. Defence in depth applies to data, not just to code paths.
- `CREATE UNIQUE INDEX users_email_lower_key ON users (LOWER(email))` — a
  **functional** index, not a plain `UNIQUE (email)`. Without `LOWER()`,
  `A@b.com` and `a@b.com` are two accounts, which is a UX bug and an
  account-takeover vector (register the uppercase variant of someone's address,
  then rely on a downstream system that lowercases). `UserRepository` matches
  the index exactly with `WHERE LOWER(u.email) = LOWER(:email)`; if Java looked
  up case-sensitively while the database enforced uniqueness case-insensitively,
  a login could miss an account that exists under another casing.

There is no plaintext password field anywhere, and `User` has no `@JsonIgnore`
on `passwordHash` — it does not need one, because entities are never returned
from a controller. Every response goes through an explicit record in
`web/dto`. That is a rule, not an accident: serialising entities is how password
hashes end up in API responses.

### `refresh_tokens`

Why store state at all, when the point of JWT is statelessness? Because
"stateless" and "revocable" are opposites, and you need both — just not in the
same token. Access tokens stay stateless and cheap (15 minutes, never checked
against the database). Refresh tokens live 7 days, so they *must* be revocable,
which requires state.

- `token_hash CHAR(64) NOT NULL UNIQUE` — a hex-encoded SHA-256. The token
  itself is never stored, so a leaked backup or a read-only SQL injection hands
  an attacker hashes they cannot present to the API.
- `replaced_by BIGINT REFERENCES refresh_tokens (id) ON DELETE SET NULL` — a
  self-referencing chain. Each use of a token revokes it and points it at its
  successor. Presenting an already-rotated token means two parties hold it, so
  `AuthService.refresh` revokes the whole family. See `02-security.md`.
- `CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id) WHERE
  revoked_at IS NULL` — partial, because the only query that scans by user is
  "revoke everything still live for this account", and revoked rows are dead
  weight in that index.

### `venues` and `seats`

A seat is a physical object bolted to a floor. It exists whether or not anything
is showing tonight. **This is why seats belong to a venue, not to an event** — a
distinction naive schemas get wrong and then have to duplicate the entire seat
map per showing by hand.

`venues.seat_count` is denormalised (derived from `COUNT(*)` on `seats`) because
the event list page shows capacity on every card and a count-per-card is a
needless query. It is written once when the seat map is created; venues do not
grow seats on their own.

`seats` carries both a *label* and *coordinates*, and the difference matters:

- `row_label VARCHAR(4)` — what is printed on the ticket: `A`, `H`, `AA`.
- `row_index SMALLINT` — what you sort by. Cinemas skip row `I` because it reads
  as the digit 1 on a printed stub, so label order and physical order are not
  the same sequence. The demo data does exactly this: rows run A, B, C…H, then
  **J**…N.
- `col_index SMALLINT` — the column position **including the centre-aisle gap**.
  The seed data shifts every seat past the halfway point one column right, so
  the frontend renders with `gridColumn: colIndex` and nothing else; the aisle
  is simply a grid column with no seat in it. No special-casing, no spacers.

Constraints: `seats_number_positive CHECK (seat_number > 0)` and
`seats_unique_position UNIQUE (venue_id, row_label, seat_number)` — the latter
stops a duplicate seat-map import from silently doubling a venue's capacity.

### `events` and `price_tiers`

An event is a *showing*: this title, in this auditorium, at this time.

- `status` with `CHECK (status IN ('DRAFT','PUBLISHED','CANCELLED'))`, and
  `category` with `CHECK (category IN ('MOVIE','CONCERT','COMEDY','SPORTS','THEATRE'))`.
  The category set is mirrored by the `EventCategory` Java enum. Both sides
  declare it so neither can drift silently: adding an enum value without a
  migration makes inserts fail loudly at the database instead of producing rows
  nobody can read back.
- `sales_close_at` is separate from `starts_at` — a venue may want to keep the
  counter open for ten minutes after the trailers roll. `CHECK (sales_close_at
  <= starts_at + INTERVAL '30 minutes')` bounds that. `Event.isOpenForSale()`
  checks `status == PUBLISHED && now.isBefore(salesCloseAt)` on **every** hold
  request, server side. The frontend hides the button too, but that is a
  courtesy to honest users; anyone can POST directly. "The UI prevents it" is
  never an access control.
- `price_tiers` belong to the **event**, not the venue, because the same
  auditorium charges differently for a Tuesday matinee and a Friday premiere.
  `colour VARCHAR(9)` lives here so the seat-map legend and the seat fill can
  never disagree — the alternative is a magic hex constant in a stylesheet that
  drifts from the price it is supposed to encode.

### `event_seats` — the bookable unit

One row per `(event, seat)`. Seat A5 at Aurora IMAX is **one** row in `seats`
but a **different** row in `event_seats` for tonight's 7pm show and tomorrow's
10am show. Booking one must not affect the other.

This is the join table that carries state, and two columns do the heavy lifting:

```sql
status  VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE'
        CHECK (status IN ('AVAILABLE', 'BOOKED', 'BLOCKED'))
version BIGINT      NOT NULL DEFAULT 0
```

`version` is the JPA optimistic lock (`@Version` on `EventSeat.version`).
Hibernate rewrites every UPDATE to this table as:

```sql
UPDATE event_seats SET status = 'BOOKED', version = 4
 WHERE id = 91 AND version = 3;     -- Hibernate adds the version predicate
```

and checks the affected row count. Two transactions that both read version 3
produce one UPDATE matching one row and one matching zero rows; the second
throws `OptimisticLockException`, which `GlobalExceptionHandler` renders as
`409 CONCURRENT_MODIFICATION`. **No lock is ever held.** Nobody waits, nothing
deadlocks, and a slow client cannot block anyone else — the opposite of
`SELECT ... FOR UPDATE`.

`event_seats_unique_per_event UNIQUE (event_id, seat_id)` is guarantee #1: a
physical chair cannot appear twice in one event. If it could, two "different"
rows would represent one chair and both could be sold.

### `bookings`

- `public_id UUID NOT NULL DEFAULT gen_random_uuid()` is the id the outside
  world sees; the `BIGSERIAL` never leaves the server. Two reasons. `/bookings/1837`
  invites someone to try `1836` — the ownership check would stop them reading it,
  but a random id makes the probe not worth writing. And sequential ids leak
  business volume: a competitor books twice a day and reads your growth rate off
  the ids.
- `reference VARCHAR(16) UNIQUE`, e.g. `SL-7QK4M2`. Generated in `Booking` from
  a 32-symbol alphabet that **excludes I, O, 0 and 1** — characters people
  mis-read and mis-dictate over the phone. Six characters gives 32⁶ ≈ 1.07
  billion combinations, and it is generated with `SecureRandom`, not
  `java.util.Random`, because references are quoted at the counter to collect
  tickets. `Random`'s internal state is recoverable from a handful of outputs.
- `expires_at` mirrors the Redis TTL so the checkout page can draw a countdown
  after a refresh without a Redis round trip, and so the reaper knows what to
  sweep. Redis remains the authority; when the two disagree, Redis is right,
  because Redis is what actually releases the seat.
- `version BIGINT` — a second optimistic lock, on the booking itself. It stops a
  double-tap on Cancel from running the cancellation twice and stops confirm and
  cancel from interleaving.
- `CHECK (status <> 'CONFIRMED' OR confirmed_at IS NOT NULL)`. Constraints like
  this stop "impossible" rows from ever existing, so downstream code never has
  to defend against them.

### `booking_seats` — the absolute floor

```sql
CREATE UNIQUE INDEX booking_seats_one_active_per_seat
    ON booking_seats (event_seat_id) WHERE active;
```

**How it works.** A partial (filtered) unique index applies its uniqueness only
to rows satisfying the `WHERE` predicate. Rows with `active = false` are simply
not in the index, so they cannot collide with anything. The consequence: one
seat may appear in *many* historical `booking_seats` rows (cancelled, expired),
but in at most **one** row with `active = true`. Postgres enforces that at the
row level on every INSERT and UPDATE, and it does so regardless of what the
application layer believes.

That is the difference between "we check carefully" and "it cannot happen".
Redis can be flushed. The `@Version` check can be bypassed by a bulk JPQL update
somebody writes in a hurry. This index cannot be bypassed by any code path at
all, including a manual `INSERT` typed at a psql prompt.

**Why the `active` column instead of joining to `bookings.status`?** Because a
unique index can only see columns of its own table, and a partial index needs
its predicate locally. There is no way to write `WHERE (SELECT status FROM
bookings ...) IN ('PENDING','CONFIRMED')` in an index predicate. So `active` is
a deliberate, one-bit denormalisation of exactly that condition.

**And the trigger keeps it honest:**

```sql
CREATE OR REPLACE FUNCTION sync_booking_seat_active() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.status IN ('CANCELLED','EXPIRED') AND OLD.status NOT IN ('CANCELLED','EXPIRED') THEN
        UPDATE booking_seats SET active = FALSE WHERE booking_id = NEW.id;
    END IF;
    RETURN NEW;
END; $$ LANGUAGE plpgsql;

CREATE TRIGGER bookings_status_sync_seats
    AFTER UPDATE OF status ON bookings FOR EACH ROW
    WHEN (OLD.status IS DISTINCT FROM NEW.status)
    EXECUTE FUNCTION sync_booking_seat_active();
```

This could live in Java. It lives in the database because it is an *invariant*,
not a business rule: there is no correct system state in which a CANCELLED
booking still holds an active seat claim. Invariants belong as close to the data
as possible, where no code path — including a manual UPDATE run by a panicking
engineer at 2am — can bypass them. `BookingPersistence.cancelBooking` never
touches `active`; `DefenceInDepthIT.cancellationReleasesTheSeatFromTheUniqueIndex`
asserts the trigger did the work and that the seat is genuinely re-bookable
afterwards.

Note also `price_minor` on `booking_seats`: the price is **copied at the moment
of sale, not referenced**. If the venue re-prices the Prime tier next week, a
ticket already issued must still show what its buyer paid. Joining to the live
tier price would silently rewrite history on every receipt ever printed.

### Why there is no `HELD` seat status

`SeatStatus` is `AVAILABLE`, `BOOKED`, `BLOCKED`. Read the missing value: there
is no `HELD`, and that absence is the central design decision of the project.

A hold is temporary, expires on its own, and happens on every checkout attempt.
If `HELD` were a database status:

- every *Proceed* would be a write transaction, and a popular drop would hammer
  Postgres with UPDATEs that are almost all going to be undone (typical cart
  abandonment on a ticketing flow is high);
- every abandoned checkout would leave a stuck row that some cleanup job has to
  notice and reverse — and if that job is down, seats stay dead **forever**;
- the row would be contended for the entire time a human spends typing card
  details, which is seconds to minutes, not milliseconds.

So holds live in Redis with a TTL, where expiry is free and automatic, and
Postgres only ever records outcomes meant to be permanent. `BLOCKED` is distinct
from `BOOKED` because no booking exists behind it (a broken recliner, a camera
position, house seats) and because the UI renders them differently: sold seats
teach the buyer the show is popular, blocked seats would just look like noise.

The `HELD` value in the seat-map API response is **composed at read time** in
`EventService.seatMap`: Postgres says AVAILABLE, one Redis `MGET` over the whole
auditorium says somebody is holding it, so the client is told HELD. It is
advisory and already slightly stale by the time it renders. That is fine — it
exists to reduce disappointment, not to enforce anything.

### `idempotency_keys`

The problem: a user taps Pay on a flaky mobile connection, the request reaches
us, we create the booking, and the response is lost. The phone retries. Without
this table they get two bookings and two charges — or, since the seats are now
gone, a confusing 409 for a booking that actually succeeded.

`UNIQUE (user_id, idem_key)` is what makes it safe under concurrency: two
simultaneous retries race to INSERT, exactly one wins, the loser reads the
winner's row and replays. `request_hash CHAR(64)` (SHA-256 of the canonical
request body) guards against a client reusing a key for a different payload,
which would otherwise let them fish for another request's stored response.
Scoping to `user_id` matters because keys are client-chosen strings: if they
were globally unique, a malicious client could claim the key `"1"` and block or
read another user's request. Full mechanics in `04-booking-lifecycle.md`.

---

## Every index, and the query it serves

| Index | Table | Serves |
|---|---|---|
| `users_email_lower_key` (UNIQUE, functional) | `users` | `UserRepository.findByEmailIgnoreCase` / `existsByEmailIgnoreCase`; enforces case-insensitive account uniqueness |
| `refresh_tokens_user_idx` (partial, `WHERE revoked_at IS NULL`) | `refresh_tokens` | `revokeAllForUser` — the bulk revoke on logout, password change, and reuse detection |
| `refresh_tokens.token_hash` (UNIQUE, inline) | `refresh_tokens` | `findByTokenHash` on every `/auth/refresh` |
| `venues_city_idx` | `venues` | the city filter on browse (`LOWER(v.city) = LOWER(:city)`) |
| `seats_venue_idx` | `seats` | building `event_seats` for a new event; seat-map joins |
| `seats_unique_position` (UNIQUE) | `seats` | integrity only — blocks a duplicate seat-map import |
| `events_status_starts_idx` (composite, `status, starts_at`) | `events` | `EventRepository.search` — the `WHERE status = ?` and the `ORDER BY starts_at` are satisfied from one index scan with **no sort step**, which is why the column order is `(status, starts_at)` and not the reverse |
| `events_venue_idx` | `events` | the venue join on browse and detail |
| `price_tiers_unique_name` (UNIQUE) | `price_tiers` | integrity; also the join key in the demo seed (`pt.name = s.section`) |
| `event_seats_unique_per_event` (UNIQUE) | `event_seats` | guarantee #1 — one chair appears once per event |
| `event_seats_event_idx` … `INCLUDE (status, version)` | `event_seats` | `findSeatMap` / `findIdsByEventId`. `INCLUDE` carries `status` and `version` in the index leaf, so Postgres can answer without touching the heap — an index-only scan |
| `event_seats_available_idx` (partial, `WHERE status = 'AVAILABLE'`) | `event_seats` | "how many seats are left?" on every event card. A partial index containing only available rows is small and stays hot in cache |
| `bookings_public_id_key`, `bookings_reference_key` (UNIQUE) | `bookings` | `findOwned` / `findByPublicId`; reference lookup at the counter |
| `bookings_user_created_idx` (`user_id, created_at DESC`) | `bookings` | `findByUser` — "my bookings, newest first", ordered straight out of the index |
| `bookings_pending_expiry_idx` (partial, `WHERE status = 'PENDING'`) | `bookings` | `findExpiredBatch`, the reaper's query. Partial because CONFIRMED bookings have `expires_at IS NULL` and are pure noise here |
| `booking_seats_unique_in_booking` (UNIQUE) | `booking_seats` | a seat appears at most once within one booking |
| **`booking_seats_one_active_per_seat`** (UNIQUE, partial) | `booking_seats` | **the oversell guarantee** |
| `booking_seats_booking_idx` | `booking_seats` | loading a booking's seat lines |
| `idempotency_unique_per_user` (UNIQUE) | `idempotency_keys` | the write-first idempotency race |
| `idempotency_created_idx` | `idempotency_keys` | `deleteOlderThan`, the nightly cleanup |

---

## JPA specifics

### `open-in-view: false`

Set in `application.yml`. Spring Boot's default is `true`, which keeps the
Hibernate session open for the whole request including view rendering. That
sounds convenient and is a trap:

- Lazy associations keep working during JSON serialisation, so you fire database
  queries **from the serialisation layer** without realising it. The N+1 problem
  becomes invisible — it does not throw, it just gets slow.
- A database connection is held from the pool of 20 for the entire request,
  including the time spent writing bytes to a slow client.

With it off, touching an uninitialised association outside a transaction throws
`LazyInitializationException` immediately. That is a *feature*: it forces every
fetch to be a decision. It is also why `BookingPersistence.loadOwned` exists and
why it explicitly touches the seat collection before returning — the mapper runs
outside the session, so what it needs must be initialised inside it. And it is
why `BookingService.confirm` re-reads the booking through `loadOwned` after
`confirmBooking` commits: the entity returned from a committed transaction is
detached, and walking its lazy graph would throw.

### `ddl-auto: validate`, never `update`

Hibernate cross-checks its entity mappings against the real tables at boot and
fails loudly if they have drifted. It never writes DDL. `update` is the tempting
alternative and is wrong in production for concrete reasons: it only ever adds,
never removes or narrows; it cannot express a data migration; it produces
different DDL depending on which Hibernate version you happen to be running; and
nobody can review it before it runs. Flyway owns the schema, full stop. The
tests run against the real migrations in a real Postgres, so a syntax error in
`V1` fails the build rather than the deploy.

### `EnumType.STRING`, never `ORDINAL`

Every `@Enumerated` in this codebase is `STRING`. `ORDINAL` stores the enum
constant's *position* as an integer. Reorder or insert a constant and every
existing row silently changes meaning — the classic way a refactor turns every
user into an admin. `STRING` also makes the database readable and lets the
`CHECK` constraints mirror the Java enums exactly.

### `LAZY` on every `@ToOne`

The JPA default for `@ManyToOne` and `@OneToOne` is **EAGER**, which is almost
always wrong. Loading 202 seats for a seat map would silently fire hundreds of
extra queries for events, venues and tiers you may not need. Every `@ManyToOne`
in `com.seatlock.domain` is explicitly `fetch = FetchType.LAZY`, and what is
actually needed is fetched deliberately with a `JOIN FETCH` or a projection.

`Booking.seats` is `@OneToMany(cascade = ALL, orphanRemoval = true, fetch =
LAZY)` — cascade because `booking_seats` rows have no life of their own, lazy so
that listing 50 bookings does not drag in every seat of every one.

### `equals` / `hashCode` by id only

Every entity uses the same pattern:

```java
public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Booking other)) return false;
    return id != null && id.equals(other.id);
}
public int hashCode() { return Booking.class.hashCode(); }
```

Three deliberate choices:

- **`instanceof`, not `getClass()`.** Hibernate hands you proxy subclasses for
  lazy associations; `getClass()` would make a proxy unequal to the entity it
  proxies.
- **`id != null &&`** — an entity that has not been persisted yet is equal only
  to itself. Without this, two unsaved entities would both have `id == null` and
  compare equal, quietly collapsing into one element of a `Set`.
- **A constant `hashCode`.** It looks wrong and is correct for entities whose id
  is assigned by the database *after* insertion. If `hashCode` depended on the
  id, an entity added to a `HashSet` before saving would land in one bucket and
  then be unfindable after the id was assigned, because its hash changed while
  it sat in the set. A constant hash degrades a `HashSet` of entities to a list
  scan, which is a real cost — and the right response is not to break the
  contract, it is not to put large numbers of entities in hash sets.

### N+1, and the exact queries that avoid it

N+1 is the most common performance bug in JPA code and the easiest to miss: the
broken version is shorter, reads perfectly well, and behaves fine with six rows
of test data. Every place it could bite here is closed by an explicit query.

**Seat map** — `EventSeatRepository.findSeatMap`:

```java
SELECT es FROM EventSeat es
  JOIN FETCH es.seat s
  JOIN FETCH es.priceTier t
 WHERE es.event.id = :eventId
 ORDER BY s.rowIndex ASC, s.colIndex ASC
```

Without the two `JOIN FETCH` clauses this returns 202 `EventSeat` rows whose
`seat` and `priceTier` are lazy proxies; the moment the mapper reads
`seat.getRowLabel()` on each, Hibernate fires another query. **1 query becomes
405.** Both associations are `@ManyToOne`, so this is a three-table join
returning one row per seat — fetching two *collections* at once would be a
different story (Hibernate would have to build a cartesian product). Ordering is
done in SQL because the database can satisfy it from an index and because
sorting 202 rows in the JVM is work repeated on every request.

**Hold and confirm** — `EventSeatRepository.findForEvent` does the same
`JOIN FETCH` for a specific id set, and note the `eventId` in the `WHERE`
clause: that is an authorisation check disguised as a filter. A request against
event 7 cannot reach a seat belonging to event 8 by passing its id.

**Browse** — three queries, never 3 + 2N. `EventService.browse` runs one paged
query (`EventRepository.search`, with `JOIN FETCH e.venue`), one grouped query
for all minimum prices (`minPriceByEventIds`), and one grouped query for all the
seat counts (`countsByEventIds`, which returns `[eventId, availableCount,
totalCount]` rows). The joins are then done in memory against two small maps.
The obvious loop would be 1 + 2×20 = **41 queries to render one screen**.

**Bookings** — `BookingRepository.findOwned` and `findByUser` both
`JOIN FETCH b.event e JOIN FETCH e.venue v`, because `BookingMapper` reads the
event title, start time and venue name for every row. `findByUser` supplies a
separate `countQuery` without the fetch joins, since counting does not need
them.

**Auth** — `RefreshTokenRepository.findByTokenHash` uses
`JOIN FETCH rt.user`, because `AuthService.refresh` immediately needs the user
to re-issue tokens and to check `enabled`.

**One honest gap.** `BookingService.listBookings` maps each booking with
`b.getSeats().size()`, which walks the lazy `booking_seats` collection and
therefore fires one extra query *per booking* — an N+1 of exactly the shape this
section is about. It works (the method is `@Transactional(readOnly = true)`, so
the session is open) and it is bounded by the page size cap of 50, but it is not
what `BookingMapper.toSummary`'s own comment claims, which says the count is
supplied "from a query that already has it". The correct fix is a grouped
`SELECT bs.booking.id, COUNT(bs) ... GROUP BY bs.booking.id` over the page's
booking ids, in the same shape as `countsByEventIds`. It is called out here
rather than hidden because a documented known gap is worth more than a silent
one.

### Bulk updates and the persistence context

Two repository methods issue bulk JPQL updates, and both carry
`@Modifying(clearAutomatically = true, flushAutomatically = true)`:

- `RefreshTokenRepository.revokeAllForUser` — done as one statement rather than
  load-loop-save because if a token has genuinely been stolen, the time between
  detecting it and killing the session should be one round trip, not one per
  token.
- `BookingRepository.expireStalePendingClaiming` — see
  `04-booking-lifecycle.md`; it closes a real window where a seat is free in
  Redis but still claimed in Postgres.

A bulk update goes straight to the database and **bypasses the persistence
context**. Without `clearAutomatically`, an entity already loaded in this
transaction would still report `revokedAt == null` — stale, and in a security
check that is the difference between revoked and not. `expireStalePendingClaiming`
additionally sets `b.version = b.version + 1` by hand, because a bulk update also
bypasses Hibernate's optimistic locking; incrementing the version manually keeps
the `@Version` contract intact so an in-flight confirmation cannot commit over
the top of the expiry.

### Batching

`spring.jpa.properties.hibernate.jdbc.batch_size: 30` with `order_inserts` and
`order_updates` set to `true`. Booking six seats becomes one JDBC round trip for
the six `booking_seats` inserts instead of six. The ordering flags matter because
JDBC batching only groups *consecutive* statements against the same table;
without reordering, an interleaved insert sequence produces batches of one.

---

## The demo data

`db/demo/V900__demo_data.sql` lives in a Flyway location that only
`application-dev.yml` adds to the search path (`classpath:db/migration,classpath:db/demo`).
Production and the test profile scan `db/migration` only, so these rows can
never reach a real database even by accident. Version 900 sits far above the
real migrations so adding V2, V3… never collides with it.

It builds three venues, each with a 202-seat auditorium generated by
`generate_series` rather than 600 literal INSERTs (2 RECLINER rows × 8 + 6 PRIME
rows × 16 + 5 CLASSIC rows × 18), six published events across those venues, three
price tiers per event (₹650 / ₹450 / ₹280), and the full `event_seats` cross
product — roughly 1 200 bookable rows. `starts_at` is relative to `NOW()` so the
demo never goes stale. About 18% of seats are marked BOOKED and two H-row seats
BLOCKED, seeded with `setseed(0.42)` so screenshots are reproducible.

Demo credentials (dev only): `user@seatlock.dev` / `Password123!` and
`admin@seatlock.dev` / `Admin123!`.
