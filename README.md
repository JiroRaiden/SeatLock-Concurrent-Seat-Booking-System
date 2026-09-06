# SeatLock

A concurrent seat-booking system — a ticketing backend that never sells the same
seat twice, and can prove it.

Fifty people are looking at the same seat map for a sold-out show. They all tap
seat **A5** inside the same second. Exactly one must end up with a booking; the
other forty-nine must get an immediate, specific answer that the seat is gone —
not a 500, not a silent failure, and above all not a second booking for the same
chair. That is the entire problem this repository exists to solve. The hard part
is not writing a booking endpoint; it is that the obvious booking endpoint —
`if (seat.isAvailable()) { book(seat); }` — is wrong, and is wrong in a way that
only appears under load, in production, on the day of a popular release. Two
requests both read `AVAILABLE`, both proceed, and one customer arrives at the
cinema to find someone in their seat. Everything else here — the schema, the
auth, the React client, the tests — is scaffolding around closing that gap.

Java 21 · Spring Boot 3.3.4 · PostgreSQL 16 · Redis 7 · React 18 + TypeScript

---

## Quick start

Requires **JDK 21**, **Maven**, **Node 20+** and **Docker**.

**1. Start Postgres and Redis.**

```bash
docker compose up -d
```

This starts only the dependencies, not the application. Compose waits for both
to report healthy before anything else uses them.

**2. Run the test suite. This is the part worth looking at.**

```bash
mvn -f backend/pom.xml verify
```

This runs the full suite under Testcontainers, including
`ConcurrentSeatBookingIT` — **fifty real HTTP requests from fifty authenticated
users, released simultaneously by a `CyclicBarrier`, all asking for the same
single seat.** It asserts exactly one `201`, exactly forty-nine `409`s carrying
`SEAT_UNAVAILABLE`, and — the assertion that actually matters — exactly one
active claim on that seat when the `booking_seats` table is queried directly. It
repeats five times, because a race that fails one run in twenty is still a race.

**Docker must be running**: the suite boots a real `postgres:16-alpine` and a
real `redis:7-alpine`, because the guarantees being tested are Postgres and
Redis behaviours that H2 and an embedded Redis do not have. Expect one to three
minutes on a warm machine. See [`docs/05-testing.md`](docs/05-testing.md).

**3. Start the API.**

```bash
mvn -f backend/pom.xml spring-boot:run
```

Defaults to the `dev` profile: local Postgres and Redis, a throwaway committed
JWT secret, and Flyway loading the demo seed (three venues, six events, ~1 200
bookable seats) on top of the real migrations. The API comes up on
`http://localhost:8080`, with Swagger UI at `/swagger-ui.html`.

**4. Start the frontend.**

```bash
cd frontend
npm install
npm run dev            # http://localhost:5173
```

Vite proxies `/api` to `:8080`, so local development is same-origin and behaves
the way production does behind nginx.

**Demo credentials** (dev profile only):

| Email | Password | Role |
|---|---|---|
| `user@seatlock.dev` | `Password123!` | `ROLE_USER` |
| `admin@seatlock.dev` | `Admin123!` | `ROLE_ADMIN` |

At checkout, the stub payment gateway approves any token except
`tok_demo_decline`, which is rejected — enough to exercise both paths.

---

## The guarantee, in three layers

SeatLock never allows two live claims on one seat, and enforces that in three
independent places:

| # | Layer | Where | Catches |
|---|---|---|---|
| 1 | Redis hold with a TTL, taken by an atomic Lua script | `hold/SeatHoldService.java`, `resources/redis/acquire_holds.lua` | virtually every real conflict, in ~1 ms |
| 2 | JPA `@Version` optimistic lock | `domain/EventSeat.java` | conflicts that got past layer 1 |
| 3 | Partial unique index | `booking_seats_one_active_per_seat` in `V1__initial_schema.sql` | everything, unconditionally |

Why three and not one: **no single mechanism is both fast enough for the hot
path and durable enough to be the system of record.** A `SELECT ... FOR UPDATE`
is correct but holds a lock — and a connection from a pool of 20 — for as long
as a human takes to type card details. A Redis hold is single-digit milliseconds
and expires by itself with no cleanup job that can be down, but Redis is a cache
with persistence bolted on: it can fail over to a replica missing recent writes,
and in that window two users genuinely both believe they hold A5. `@Version`
makes that rare case correct without ever holding a lock — the loser matches
zero rows and gets a retryable 409. The partial unique index is the floor:
`CREATE UNIQUE INDEX ... ON booking_seats (event_seat_id) WHERE active` cannot
be bypassed by any code path at all, including a manual `INSERT` at a psql
prompt.

Layer 1 is a performance optimisation with correctness consequences, layer 2 is
correctness under contention, layer 3 is correctness under everything. The full
argument, the Lua scripts line by line, and why `SET NX` alone is not enough:
[`docs/03-concurrency.md`](docs/03-concurrency.md).

---

## Documentation

The reasoning lives in `docs/`, and it is the point of this repository as much
as the code is. Read them in this order.

| Document | What it covers |
|---|---|
| [`docs/API.md`](docs/API.md) | The endpoint contract: the booking flow, the single error envelope, every error code, rate limits |
| [`docs/00-overview.md`](docs/00-overview.md) | What the system is, the architecture diagram, a booking end to end, the phase map |
| [`docs/01-data-model.md`](docs/01-data-model.md) | The schema, why `event_seats` is the bookable unit, every index and the query it serves, the JPA specifics |
| [`docs/02-security.md`](docs/02-security.md) | A threat model: for each attack, the defence, the file that implements it, and the residual risk |
| [`docs/03-concurrency.md`](docs/03-concurrency.md) | The three layers in detail, the Lua scripts, and why `SET NX` alone is not enough |
| [`docs/04-booking-lifecycle.md`](docs/04-booking-lifecycle.md) | The state machine, hold and confirm step by step, idempotency, the reaper — and the crash-point failure analysis |
| [`docs/05-testing.md`](docs/05-testing.md) | Why the `CyclicBarrier` is the whole test, Testcontainers over H2, what each test proves, and what is not tested |
| [`docs/07-deployment.md`](docs/07-deployment.md) | AWS topology, security groups, secrets, a runbook, readiness vs liveness, and the `X-Forwarded-For` trap |
| [`docs/INTERVIEW.md`](docs/INTERVIEW.md) | The questions and the answers, condensed |
| [`frontend/README.md`](frontend/README.md) | The client: seat-map geometry, the procedural poster system, and the token-storage trade-off |

If you have twenty minutes: `00-overview.md`, then the three-layer section of
`03-concurrency.md`, then the failure analysis in `04-booking-lifecycle.md`.

---

## Project structure

```
seatlock/
├── backend/
│   ├── pom.xml                        Surefire (*Test) / Failsafe (*IT) split
│   └── src/
│       ├── main/
│       │   ├── java/com/seatlock/
│       │   │   ├── SeatLockApplication.java
│       │   │   ├── booking/           BookingService (orchestration, no @Transactional)
│       │   │   │                      BookingPersistence (the transactional units of work)
│       │   │   │                      IdempotencyService, HoldExpiryReaper
│       │   │   │                      PaymentGateway + StubPaymentGateway
│       │   │   │                      EventService, BookingMapper
│       │   │   ├── config/            SecurityConfig, RateLimitFilter, RedisConfig,
│       │   │   │                      HoldProperties, SecurityProperties
│       │   │   ├── domain/            JPA entities and enums (EventSeat carries @Version)
│       │   │   ├── exception/         ApiException, ErrorCode
│       │   │   ├── hold/              SeatHoldService, HoldResult
│       │   │   ├── repository/        Spring Data repositories, all queries named
│       │   │   ├── security/          JwtService, AuthService, JwtAuthenticationFilter
│       │   │   └── web/               controllers, GlobalExceptionHandler, dto/ (records)
│       │   └── resources/
│       │       ├── application.yml            base — no secret has a default
│       │       ├── application-dev.yml        local values, throwaway JWT secret
│       │       ├── application-prod.yml       tightens; supplies nothing
│       │       ├── db/migration/V1__initial_schema.sql
│       │       ├── db/demo/V900__demo_data.sql    dev profile only
│       │       └── redis/                     acquire / release / extend .lua
│       └── test/
│           ├── java/com/seatlock/
│           │   ├── booking/ConcurrentSeatBookingIT.java    the 50-thread test
│           │   ├── booking/DefenceInDepthIT.java           layers 2 and 3, Redis bypassed
│           │   └── support/                                IntegrationTestBase, TestFixtures
│           └── resources/application-test.yml
├── frontend/
│   ├── Dockerfile                     multi-stage: Node builds, nginx serves
│   ├── nginx.conf                     SPA fallback, CSP, cache policy
│   └── src/
│       ├── api/                       endpoints.ts, zod schemas.ts
│       ├── auth/                      AuthContext, RequireAuth
│       ├── components/                Header, Rail, EventCard, PosterArt, Countdown
│       ├── features/seatmap/          geometry.ts (the row curve), Seat, Legend, ScreenArc
│       ├── lib/                       apiClient (refresh mutex), tokenStore, hash, format
│       ├── pages/                     one file per route
│       └── styles/                    theme.css is the only file with hex values
├── docs/                              00, 01, 02, 03, 04, 05, 07, API, INTERVIEW
├── docker-compose.yml                 Postgres + Redis; --profile full adds the apps
└── .env.example                       every variable, no values
```

---

## Tech stack, and what each choice beat

| Piece | Version | Beat | Because |
|---|---|---|---|
| Java | 21 | 17 | Records for every DTO, pattern-matching `switch`, and virtual threads available if the request model ever needs them |
| Spring Boot | 3.3.4 | Quarkus, plain Servlet | The starter parent pins ~300 library versions, and the security filter chain, Data JPA and Actuator would otherwise have to be built badly by hand |
| PostgreSQL | 16 | MySQL | **Partial unique indexes.** `CREATE UNIQUE INDEX ... WHERE active` is the strongest guarantee in this system and MySQL has no equivalent |
| Redis | 7 | An in-JVM map of locks | An in-JVM lock is correct on exactly one instance; the moment you run two, two users hold the same seat. TTL-based expiry is also free |
| Flyway | Boot-managed | `hibernate.ddl-auto=update` | `update` only ever adds, cannot express a data migration, and cannot be reviewed. `ddl-auto` is `validate` so Hibernate fails loudly on drift |
| JJWT | 0.12.6 | `oauth2-resource-server` | The OAuth2 starter is right with an external identity provider; here SeatLock *is* the issuer. `verifyWith(SecretKey)` also closes the `alg:none` hole at the API level |
| Bucket4j | 8.10.1 | A hand-rolled fixed-window counter | A token bucket refills continuously, so there is no window boundary to game (100 at 11:59:59 plus 100 at 12:00:00) |
| Caffeine | Boot-managed | `ConcurrentHashMap` | The rate limiter's key comes from a client-controlled IP; an unbounded map turns the anti-abuse component into the amplifier. Hard cap 100 000, 10-minute idle eviction |
| Testcontainers | Boot-managed | H2 + embedded Redis | H2 cannot create a partial unique index, so the suite would pass without the constraint it exists to prove |
| React 18 + TS + Vite | 18.3.1 / 5.9 / 5.4 | Next.js | No SSR requirement and no SEO behind a login. A static bundle on S3/CloudFront is cheaper and has no Node runtime to operate |
| TanStack Query + zod | 5.62 / 3.25 | Redux, hand-written fetch | Server state is cache state, not application state. zod validates every response at runtime, so a contract change surfaces at the boundary |
| Micrometer + Prometheus | Boot-managed | grepping logs | `seatlock.holds.expired` answers "is the 8-minute TTL right?", which cannot be reconstructed from logs after the fact |

---

## Scope and limitations

This is a portfolio project built to demonstrate a specific problem well, not a
product. What it is not, stated plainly:

- **It cannot take money.** `StubPaymentGateway` is a plain `@Component` with no
  profile condition, so it is active everywhere — deliberately, and named so
  nobody can mistake it for real. A live provider goes behind the existing
  `PaymentGateway` interface.
- **One failure window is knowingly accepted.** A crash between committing the
  booking and capturing the payment gives away a free seat. It is placed last on
  purpose, and the production answer — an outbox table plus a settlement job —
  is described but not built. See `docs/04-booking-lifecycle.md`.
- **Rate limiting is per-instance.** Buckets live in the JVM heap, so three
  instances mean 3× the configured limit. The fix (Bucket4j's Redis backend) is
  named in `RateLimitFilter`'s own javadoc.
- **The scheduled reaper runs on every instance.** Harmless today because it is
  idempotent; ShedLock is the fix.
- **The refresh token lives in `localStorage`** and is stealable by XSS. The
  correct fix is an httpOnly cookie, which needs a shared parent domain and
  reintroduces CSRF — infrastructure work, not a code change. `docs/02-security.md`
  section 18 argues it rather than hiding it.
- **Access tokens cannot be revoked.** Logout kills the refresh token
  immediately; the 15-minute access token stays valid. Its lifetime is its blast
  radius.
- **There are no unit tests, and no CI.** Two integration test classes cover the
  central claim; auth, idempotency, rate limiting, the payment paths and the
  reaper are untested. `docs/05-testing.md` lists every gap.
- **There is no backend Dockerfile**, so `docker compose --profile full up`
  builds only the frontend. The Postgres + Redis half works.
- **No admin UI and no admin endpoints.** `ROLE_ADMIN` exists and gates
  `/actuator/**`, but events and seat maps arrive through migrations.
- **Swagger UI is public**, which is right for a project meant to be explored
  and wrong in production.
- **Known bug:** cancelling a `CONFIRMED` booking returns 500 rather than 200.
  `Booking.cancel` rejects any status for which `isTerminal()` is true, and
  `isTerminal()` includes `CONFIRMED`. Cancelling a `PENDING` booking works.
  Recorded in `docs/04-booking-lifecycle.md` rather than papered over.

The list is longer than most READMEs carry, on purpose. Knowing which
limitations you chose, and why, is the difference between a system you built and
a system you assembled.
