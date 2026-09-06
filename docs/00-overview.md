# SeatLock — overview

Start here. This document explains what the system is, the one problem it exists
to solve, how the pieces fit together, and where to read next.

---

## The problem

Fifty people are looking at the same seat map for a sold-out show. They all tap
seat **A5** within the same second. Exactly one of them must end up with a
booking for A5; the other forty-nine must get a clear, immediate answer that the
seat is gone — not a 500, not a silent failure, and above all not a second
booking for the same chair. That is the whole problem. Everything else in this
repository — the schema, the auth, the frontend, the tests — is scaffolding
around that guarantee. The hard part is not "write a booking endpoint"; it is
that the naive booking endpoint (`if (seat.isAvailable()) { book(seat); }`) is
wrong, and is wrong in a way that only shows up under load, in production, on
the day of a popular release.

---

## The three-layer defence

SeatLock never allows two live claims on one seat, and it enforces that in three
independent places:

| # | Layer | Where | Catches |
|---|---|---|---|
| 1 | Redis hold with a TTL | `hold/SeatHoldService.java`, `resources/redis/acquire_holds.lua` | virtually every real conflict, in ~1 ms |
| 2 | JPA `@Version` optimistic lock | `domain/EventSeat.java` (`version` column) | conflicts that got past layer 1 |
| 3 | Partial unique index | `booking_seats_one_active_per_seat` in `V1__initial_schema.sql` | everything, unconditionally |

**Why three and not one?** Because no single mechanism is both fast enough for
the hot path and durable enough to be the system of record, and trying to make
one mechanism do both job produces something bad at each.

- A **database row lock** (`SELECT ... FOR UPDATE`) is correct but is held until
  the transaction ends. Here the "transaction" contains a human typing card
  details and waiting for an OTP — seconds to minutes. Each such lock parks one
  connection from a pool of 20 (`DB_POOL_SIZE`, `application.yml`) and blocks
  every other reader of that row. A few hundred concurrent checkouts would
  exhaust the pool and take the whole service down, not just the contested seat.
- A **Redis hold** is the opposite: single-digit milliseconds, and expiry is
  free — Redis deletes the key itself when the TTL elapses, with no cleanup job
  that can be down. But Redis is a cache with persistence bolted on. It can fail
  over to a replica missing the last few writes, or restart with an empty
  dataset. In those windows two users genuinely can both believe they hold A5.
  Redis makes conflicts **rare and cheap**; it cannot make them **impossible**.
- `@Version` makes the rare case **correct** without ever holding a lock:
  Hibernate appends `AND version = ?` to the UPDATE, the loser matches zero rows
  and gets an `OptimisticLockException` which becomes a retryable 409. Nobody
  waits, nothing deadlocks.
- The **partial unique index** is the floor. `CREATE UNIQUE INDEX
  booking_seats_one_active_per_seat ON booking_seats (event_seat_id) WHERE
  active;` cannot be bypassed by any code path at all — not by a native query, a
  future contributor in a hurry, or a manual `INSERT` at 2am. Redis can be
  flushed and application checks can be forgotten. This cannot.

So: layer 1 is a performance optimisation with correctness consequences, layer 2
is correctness under contention, layer 3 is correctness under everything. Full
detail is in `docs/03-concurrency.md`.

---

## Architecture

```
   +---------------------------+
   |  Browser                  |
   |  React 18 + TS SPA        |
   |  (Vite dev :5173, or      |
   |   nginx-served static)    |
   +-------------+-------------+
                 |  HTTPS, JSON, Bearer access token
                 |  (dev: Vite proxies /api -> :8080, so same-origin)
                 v
   +---------------------------------------------------------+
   |  Spring Boot 3.3.4 API (:8080)                           |
   |                                                          |
   |   RateLimitFilter  ->  JwtAuthenticationFilter  ->        |
   |   Controllers (web/)                                      |
   |     |                                                     |
   |     +-- EventService ------- browse / seat map            |
   |     +-- BookingService ----- orchestrates the flow        |
   |            |         |            |                       |
   |            |         |            +--> PaymentGateway     |
   |            |         |                 (StubPaymentGateway)|
   |            |         +--> BookingPersistence (@Transactional)
   |            +--> SeatHoldService                            |
   |   HoldExpiryReaper (@Scheduled, every 60s)                 |
   +--------+--------------------------------+------------------+
            |                                |
            | Lettuce (EVALSHA Lua)          | HikariCP (max 20)
            v                                v
   +-----------------+            +-----------------------------+
   |  Redis 7        |            |  PostgreSQL 16              |
   |                 |            |                             |
   |  seatlock:hold: |            |  users, venues, seats,      |
   |  {evt:42}:9137  |            |  events, price_tiers,       |
   |    = <holdId>   |            |  event_seats, bookings,     |
   |    PX 480000    |            |  booking_seats,             |
   |                 |            |  refresh_tokens,            |
   |  volatile,      |            |  idempotency_keys           |
   |  self-expiring  |            |                             |
   |                 |            |  system of record           |
   +-----------------+            +-----------------------------+
```

Redis holds nothing durable: if you deleted the entire Redis dataset, no booking
would be lost. Every seat that is genuinely sold is sold in Postgres.

---

## A booking, end to end

```
1.  GET  /api/v1/events?city=Kolkata&page=0&size=20
        EventService.browse — 3 queries total, never 3 + 2N.

2.  GET  /api/v1/events/{id}/seatmap
        One SQL query (JOIN FETCH seat + tier) + one Redis MGET over ~202 keys.
        Seat status is composed: Postgres says AVAILABLE, Redis says someone
        holds it -> the client is told HELD. HELD is not a database state.

3.  ...user clicks seats...
        NO network calls. Selection is local React state. Locking on every tap
        would generate thousands of throwaway reservations per second during a
        drop. See docs/API.md.

4.  POST /api/v1/events/{id}/holds     { "seatIds": [4401, 4402] }
        BookingService.createHold:
          a. validate (1..10 seats, sales still open)
          b. read availability from Postgres (cheap, already stale)
          c. acquire ALL seats in Redis via one atomic Lua script  <-- the gate
          d. write the PENDING booking + booking_seats rows to Postgres
          e. if (d) fails, release (c) — compare-and-delete on our own token
        -> 201 { holdId, expiresAt, ttlSeconds: 480, totalMinor, seats[] }
        holdId is ALSO the booking's public_id and the Redis ownership token.

5.  POST /api/v1/bookings/{holdId}/confirm    Idempotency-Key: <uuid>
        BookingService.confirm:
          idempotency gate -> hold still ours? -> AUTHORIZE payment ->
          commit booking (PENDING->CONFIRMED, seats->BOOKED) -> CAPTURE ->
          release Redis hold -> store the response for replay
        -> 200 BookingDetail

    Meanwhile, if the user walks away: the Redis TTL frees the seat at t+8m by
    itself; HoldExpiryReaper tidies the orphaned PENDING row within 60s.
```

The full endpoint contract is `docs/API.md`. The failure analysis for step 5 —
what state survives a crash at each point — is `docs/04-booking-lifecycle.md`,
and it is the most interview-relevant section in this repository.

---

## Phase map

The project was built in phases. Each row says what it produced and where to
read about it.

| Phase | Built | Key files | Doc |
|---|---|---|---|
| 0 — Foundation & infra | Maven build, local Postgres + Redis, profile/config strategy, secret handling | `backend/pom.xml`, `docker-compose.yml`, `.env.example`, `.gitignore`, `application{,-dev,-prod}.yml`, `SeatLockApplication.java` | this file, `07-deployment.md` |
| 1 — Data model | Flyway schema, all entities, repositories, demo seed | `db/migration/V1__initial_schema.sql`, `db/demo/V900__demo_data.sql`, `domain/*`, `repository/*` | `01-data-model.md` |
| 2 — Auth | Registration/login, access + refresh tokens, rotation with reuse detection, filter chain, CORS, rate limiting | `security/*`, `config/SecurityConfig.java`, `config/RateLimitFilter.java`, `config/SecurityProperties.java`, `web/AuthController.java` | `02-security.md` |
| 3 — Concurrency core | Redis hold service, three Lua scripts, `@Version`, the partial unique index | `hold/SeatHoldService.java`, `resources/redis/*.lua`, `config/RedisConfig.java`, `domain/EventSeat.java`, `domain/BookingSeat.java` | `03-concurrency.md` |
| 4 — Booking lifecycle | Hold → confirm → cancel, idempotency, authorize/capture, the reaper | `booking/BookingService.java`, `booking/BookingPersistence.java`, `booking/IdempotencyService.java`, `booking/PaymentGateway.java`, `booking/HoldExpiryReaper.java` | `04-booking-lifecycle.md` |
| 5 — Testing | Testcontainers harness, the 50-thread contention test, the defence-in-depth tests | `src/test/java/com/seatlock/**`, `application-test.yml`, Surefire/Failsafe config in `pom.xml` | `05-testing.md` |
| 6 — Frontend | React SPA: browse, seat map with a real row curve, checkout, bookings | `frontend/src/**` | `frontend/README.md` |
| 7 — Deployment | Production profile, graceful shutdown, container build, nginx + CSP, AWS topology | `application-prod.yml`, `frontend/Dockerfile`, `frontend/nginx.conf`, `docker-compose.yml` (`full` profile) | `07-deployment.md` |
| 8 — Hardening & interview prep | One error envelope, information-disclosure rules, response headers, actuator exposure, the written record of every trade-off | `web/GlobalExceptionHandler.java`, `exception/ErrorCode.java`, `docs/*` | `02-security.md`, `docs/INTERVIEW.md` |

---

## Reading guide

Read in this order. Each document assumes the one before it.

1. **`docs/API.md`** — the contract. Everything else is an explanation of how
   this contract is kept.
2. **`docs/00-overview.md`** — this file.
3. **`docs/01-data-model.md`** — the schema, and why `event_seats` rather than
   `seats` is the bookable unit. The concurrency story does not make sense until
   you have this.
4. **`docs/03-concurrency.md`** — the three layers in detail, the Lua scripts,
   and why `SET NX` alone is not enough.
5. **`docs/04-booking-lifecycle.md`** — the ordering of operations, and the
   failure-mode analysis. Read the crash-point walkthrough slowly.
6. **`docs/02-security.md`** — threat model, defences, residual risk.
7. **`docs/05-testing.md`** — how any of the above is actually proven, and the
   `CyclicBarrier` point about why most "concurrency tests" test nothing.
8. **`docs/07-deployment.md`** — AWS topology and the runbook.
9. **`frontend/README.md`** — the client, the seat-map geometry, and the honest
   token-storage trade-off.
10. **`docs/INTERVIEW.md`** — the questions and the answers, condensed.

If you only have twenty minutes: `00`, then the "three-layer defence" section of
`03`, then the failure-mode analysis in `04`.

---

## Tech stack, and what each choice beat

| Piece | Version | Chosen over | Why |
|---|---|---|---|
| Java | 21 | 17 | Records, pattern-matching `switch`, and virtual threads available if the request model ever needs them. Every DTO in `web/dto` is a record. |
| Spring Boot | 3.3.4 | Quarkus, plain Servlet | The starter parent pins ~300 library versions, and the Spring Security filter chain, Data JPA and Actuator are all things this project would otherwise have to build badly. |
| PostgreSQL | 16 | MySQL | **Partial unique indexes.** `CREATE UNIQUE INDEX ... WHERE active` is the strongest guarantee in this system and MySQL has no equivalent. Also `TIMESTAMPTZ`, `gen_random_uuid()`, and `INCLUDE` columns on indexes. |
| Redis | 7 | An in-JVM `ConcurrentHashMap` of locks | An in-JVM lock is correct on exactly one instance. The moment you run two, two users can hold the same seat. Redis is also where TTL-based expiry is free. |
| Flyway | (Boot-managed) | `hibernate.ddl-auto=update` | `ddl-auto` guesses at the schema, never drops anything, and cannot be reviewed. Flyway migrations are append-only, checksummed, in git, and run identically everywhere. `ddl-auto` is set to `validate` so Hibernate cross-checks its mappings at boot and fails loudly on drift. |
| JJWT | 0.12.6 | `spring-boot-starter-oauth2-resource-server` | The OAuth2 starter is right when there is an external identity provider. Here SeatLock *is* the issuer, so all it would add is JWK plumbing. JJWT 0.12's `verifyWith(SecretKey)` also binds verification to a MAC algorithm at the API level, which is what closes the `alg:none` hole. |
| Bucket4j | 8.10.1 | A hand-rolled fixed-window counter | Token bucket refills continuously, so it has no window boundary to game (100 at 11:59:59 plus 100 at 12:00:00). |
| Caffeine | (Boot-managed) | `ConcurrentHashMap` | The rate limiter's map key is derived from a client-controlled IP. An unbounded map grows until the heap dies — the anti-abuse component becomes the amplifier. Caffeine gives a hard cap (100 000) and 10-minute idle eviction. |
| Testcontainers | (Boot-managed) | H2 + embedded Redis | H2 cannot create a partial unique index, so the suite would pass without the constraint it exists to prove. See `05-testing.md`. |
| React 18 + TypeScript + Vite | 18.3.1 / 5.9 / 5.4 | Next.js | There is no server-side rendering requirement and no SEO requirement behind a login. A static bundle on S3/CloudFront is cheaper, simpler, and has no Node runtime to operate. |
| TanStack Query + zod | 5.62 / 3.25 | Redux, hand-written fetch | Server state is cache state, not application state. zod validates every response at runtime, so a contract change surfaces at the boundary rather than as `undefined is not an object` three components deep. |
| Micrometer + Prometheus | (Boot-managed) | log grepping | `seatlock.holds.expired` answers "is the 8-minute TTL right?", which you cannot answer retroactively from logs. |
