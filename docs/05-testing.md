# Testing

The claim this project makes is: *fifty people click seat A5 at the same instant
and exactly one of them gets it.* A test suite that does not actually create that
race proves nothing, and most "concurrency tests" do not create it. This document
is about how these ones do, what each test class establishes, and — at the end —
what is honestly not covered.

Everything here lives in `backend/src/test/java/com/seatlock/` and
`backend/src/test/resources/application-test.yml`.

---

## The pyramid, and the shape this project actually has

The usual advice is a wide base of fast unit tests, a narrower band of
integration tests, and a handful of end-to-end tests. It is good advice, and it
follows from a real cost curve: unit tests are cheap to write, cheap to run, and
point at the exact line that broke.

**This suite is deliberately inverted, and it is worth being straight about
why.** There are two test classes, both integration tests:

```
                    /\
                   /  \        (none — no UI automation)
                  /----\
                 /      \      ConcurrentSeatBookingIT   6 assertions over
                /  INT   \     DefenceInDepthIT          real HTTP + real
               /          \                              Postgres + real Redis
              /------------\
             /              \   (currently empty — see "What is not tested")
            /     UNIT       \
           /------------------\
```

The reasoning: **the guarantees this project makes are not properties of Java
code.** "A seat cannot be claimed twice" is a property of a Postgres partial
unique index. "A hold is acquired atomically or not at all" is a property of
Redis executing a Lua script on a single command thread. "The loser of a race
gets a clean 409" is a property of how Hibernate's `@Version` predicate,
`GlobalExceptionHandler` and the Spring filter chain compose. None of those can
be established by a test that mocks the thing doing the work — you would be
testing the mock.

That is a justification for the integration tests existing, not for the unit
tests being absent. The absence is a gap, and it is listed at the bottom.

---

## Surefire vs Failsafe: `*Test` and `*IT`

`backend/pom.xml` splits the two by naming convention:

```xml
<maven-surefire-plugin>   excludes **/*IT.java   </maven-surefire-plugin>
<maven-failsafe-plugin>   includes **/*IT.java   </maven-failsafe-plugin>
```

| | Surefire | Failsafe |
|---|---|---|
| Runs classes named | `*Test` | `*IT` |
| Bound to phase | `test` | `integration-test` + `verify` |
| Needs Docker | no | **yes** |
| A failure | fails the build immediately | fails at `verify`, *after* the `post-integration-test` phase has had a chance to tear down |

Why the split matters:

1. **The inner loop stays fast.** `mvn test` is meant to be something you run
   every few minutes. Booting two containers and two Spring contexts is not that.
2. **Failsafe fails late on purpose.** Surefire aborts the build the moment a
   test fails, which would leave containers, ports and fixture data behind.
   Failsafe records the failure, lets teardown run, and only then fails the
   build at `verify`. With Testcontainers that is less critical (Ryuk cleans up
   on JVM exit) but it is the reason the plugin exists and the reason CI can
   still publish a report from a failed run.
3. **It documents the dependency.** A class named `ConcurrentSeatBookingIT` says
   "I need infrastructure" in its filename. Nobody has to discover that from a
   stack trace.

**Honest note:** there are currently **no `*Test` classes at all**, so `mvn test`
compiles everything and runs zero tests. The split is correctly configured and
the convention is worth keeping; the fast half is simply not populated yet.

---

## Testcontainers, not H2 + embedded Redis

`support/IntegrationTestBase.java` starts a real `postgres:16-alpine` and a real
`redis:7-alpine`. The four reasons are in that class's javadoc and each one is
concrete:

1. **Partial unique indexes.** `CREATE UNIQUE INDEX ... WHERE active` is
   Postgres syntax. H2 cannot create it. So the single strongest oversell
   guarantee in the system would simply **not exist** under test — and a suite
   that passes without the constraint it is meant to prove is worse than no
   suite, because it produces confidence that is not warranted.
2. **MVCC and optimistic-lock timing.** The whole point of the concurrency test
   is what two transactions do to each other. H2's concurrency model is not
   Postgres's, so a green run would tell you nothing about production.
3. **Lua script semantics.** Embedded Redis substitutes have historically
   stubbed or approximated `EVAL`. The correctness argument in
   `docs/03-concurrency.md` rests on Redis executing a script atomically on a
   single thread. Testing that against a fake tests the fake.
4. **Flyway.** Running the real migrations against the real engine puts the
   migrations themselves under test. A syntax error in `V1__initial_schema.sql`
   fails the build rather than the deploy.

Three implementation choices in that base class are worth knowing:

- **The containers are `static` with a manual `start()` in a static
  initialiser.** One Postgres and one Redis for the whole run, reused across
  classes; Testcontainers shuts them down through its Ryuk sidecar when the JVM
  exits. A fresh pair per class would add roughly four seconds per class for no
  isolation benefit, since the tests clean up their own data.
- **Ports are random**, fed into Spring by `@DynamicPropertySource`. A fixed
  5432 would collide with a Postgres already running on the developer's machine
  and CI would fail in a way that never reproduces locally.
- **`postgres -c fsync=off -c full_page_writes=off`.** A test database does not
  need to survive a crash, and turning durability off makes the suite noticeably
  faster. The comment in the file says "never, ever do this to a real database",
  which is the right way to leave that line in a repository.

`application-test.yml` completes the picture: Flyway loads `db/migration`
**only**, deliberately excluding the demo data in `db/demo`, so tests start from
an empty database and create exactly the fixtures they need. A test that passes
because of seed data it did not create is a test that breaks the day somebody
edits the seed. The reaper's `fixed-delay` is set to `3600s` — effectively never
— so a background job cannot interfere with a test's timing, and rate limits are
raised to 10 000/minute so the fifty-request test is not throttled by the very
filter that is supposed to protect production.

---

## The `CyclicBarrier` insight

This is the most important idea in the test suite, and it is the thing most
"concurrency tests" get wrong.

### Submitting N tasks to a pool does not create a race

```java
ExecutorService pool = Executors.newFixedThreadPool(50);
for (int i = 0; i < 50; i++) {
    pool.submit(() -> bookSeat(seatId));   // looks concurrent. isn't.
}
```

An `ExecutorService` starts tasks **as threads become available**. Submission is
sequential and near-instant, but each task then has to build its request object,
resolve a host, open a socket, and get scheduled. In practice the first task is
frequently finished — booking committed, seat gone — before the fiftieth has
even entered its body.

What you then observe is one 201 and forty-nine 409s, the test goes green, and
you have proved nothing at all: the same result would appear from a completely
serial implementation with no locking whatsoever. The test cannot distinguish a
correct system from a broken one, which makes it worse than absent, because it
looks like coverage.

This is not hypothetical timing pedantry. On a warm JVM the request setup in
`ConcurrentSeatBookingIT` — building an `HttpRequest`, serialising a body —
takes tens of microseconds, and the whole hold path takes a few milliseconds.
Fifty sequential submissions can easily spread over more wall-clock time than
one full request takes to complete.

### What the barrier does

```java
CyclicBarrier startLine = new CyclicBarrier(CONTENDERS);   // 50
...
HttpRequest request = /* all per-thread setup happens here */;
startLine.await(20, TimeUnit.SECONDS);        // block until all 50 have arrived
responses.add(http.send(request, ofString()));
```

`CyclicBarrier` is a rendezvous point. Each of the fifty threads does its setup,
calls `await()`, and **blocks**. The barrier counts arrivals; when the fiftieth
arrives, it releases all fifty at once. Every thread resumes from the same
instant, with all of its setup already done, and the only work left is
`http.send`. The fifty requests hit the endpoint inside the same few hundred
microseconds — a genuine race, deliberately manufactured.

The ordering is the point: **everything expensive goes before the barrier,
nothing but the contended operation goes after it.**

### Why the pool must be at least as large as the barrier count

```java
ExecutorService pool = Executors.newFixedThreadPool(CONTENDERS);  // 50 == 50
```

The barrier only releases when 50 threads are simultaneously waiting on it. With
a pool of, say, 10, ten tasks start, all ten block in `await()`, and no thread is
ever free to run task 11. The barrier never reaches 50, the ten blocked threads
never return, and the pool never frees a thread — **deadlock**.

It resolves here only because `await` is given a 20-second timeout: after twenty
seconds every waiting thread throws `BrokenBarrierException`/`TimeoutException`,
the catch increments `unexpectedErrors`, and the test fails on the
`isZero()` assertion rather than hanging the build forever. That timeout is a
safety net, not the mechanism.

The rule: **pool size ≥ barrier parties**, always. `DefenceInDepthIT` obeys the
same rule at a smaller scale — `newFixedThreadPool(2)` with
`new CyclicBarrier(2)`.

A `CountDownLatch` handles the other end. `finished` counts down in a `finally`
block as each request *completes*, so the main thread does
`finished.await(60, SECONDS)` instead of polling or sleeping — and the assertion
that it returned `true` is itself a check that nothing hung.

---

## Test by test

### `ConcurrentSeatBookingIT` — `@SpringBootTest(webEnvironment = RANDOM_PORT)`

Runs a real embedded Tomcat on a random port and drives it with the JDK
`HttpClient`. Real HTTP means the full stack is exercised: `RateLimitFilter`,
`JwtAuthenticationFilter`, bean validation, the controller, the service, Redis,
Postgres, and `GlobalExceptionHandler` on the way back.

`@BeforeEach` does two things: `fixtures.reset()`, and a Redis `FLUSHALL`. The
second matters — **holds outlive a transaction rollback, because Redis has no
transaction to roll back**. Left behind, they would make the next test look like
it lost a race to a ghost.

**`exactlyOneWinnerUnderMaximumContention`** (`@RepeatedTest(5)`). Fifty users,
fifty real access tokens, one seat, one barrier. Four assertions:

1. **Exactly one 201.** One winner.
2. **Exactly forty-nine 409s, every one with `code: "SEAT_UNAVAILABLE"` and a
   `details.unavailableSeatIds` array.** Not 500s. Losing a race is a normal
   outcome and must look like one — a specific, actionable, machine-readable
   answer that names exactly which seat was lost so the client can prune it from
   the selection. Asserting on the *code* rather than just the status is what
   makes this test also a test of the error contract in `docs/API.md`.
3. **Exactly one active claim on the seat in `booking_seats`.** See below.
4. **Exactly one key matching `seatlock:hold:*` in Redis.** No leaked holds.

**`multiSeatHoldIsAtomic`.** Alice takes seat C out of the middle of Bob's
intended A, B, C, D. Bob must get **none** of them. The test asserts Bob's 409
names exactly seat C, and then — the part that actually matters — that A, B and
D have zero active claims and that Redis holds exactly one key. A partial hold
would have taken three seats off sale for a booking that can never complete,
which is a worse failure than a clean rejection.

Tokens are minted directly through `JwtService.issueAccessToken` rather than by
calling `/auth/login` fifty times. Fifty logins would spend fifty rounds of
cost-12 BCrypt — about twelve seconds — to test something this test is not
about. The tokens come from the same service the application uses and are
verified by the real filter chain, so authentication is still genuinely
exercised.

### `DefenceInDepthIT` — no web layer, straight at `BookingPersistence`

**Why this matters more than it looks.** `ConcurrentSeatBookingIT` proves the
system as a whole does not oversell — but it runs **with Redis healthy**. Redis
catches essentially every conflict in `acquire_holds.lua`, in about a
millisecond, before any database code runs. Layers 2 and 3 are never reached.

Which means: **a green run of the fifty-thread test would look completely
identical if the `@Version` column and the partial unique index were quietly
broken.** Delete the `@Version` annotation, drop the index, and that test still
passes. That is precisely the situation you do not want — a headline test that
cannot fail for the reason it claims to protect against.

The interesting state is the one Redis produces when it is unhealthy: a failover
to a replica missing recent writes, or a restart with an empty dataset. In that
window two requests genuinely can both believe they hold seat A5. So these tests
**skip the hold layer entirely** and go straight at `BookingPersistence`, which
is exactly the state a Redis failure leaves.

- **`uniqueIndexPreventsTwoActiveClaims` (layer 3).** Two threads, one barrier,
  both call `createPendingBooking` for the same seat with different users. One
  succeeds, one is rejected; the rejection type is captured, and the final
  assertion is `activeClaimsOn(seatId) == 1`. The catch clauses accept a
  `DataIntegrityViolationException` (the index fired), an
  `OptimisticLockingFailureException`, or an `ApiException` — the last because
  the in-transaction availability re-read can catch it before the constraint has
  to. All three are correct rejections; only "two rows" is a failure.
- **`optimisticLockRejectsTheSecondWriter` (layer 2).** Two
  `TransactionTemplate` transactions, and the barrier is placed **after both
  have read** the seat and **before either writes**: `read → barrier → write`.
  Without that placement the first transaction usually commits before the second
  one reads, no version conflict ever occurs, and the test passes while proving
  nothing — the same failure mode as submitting tasks to a pool, one level down.
  `saveAndFlush` forces the UPDATE inside the transaction so the failure is
  attributable to that statement rather than to commit. An `IllegalStateException`
  from `EventSeat.markBooked()` is also counted as a correct rejection: it means
  the loser re-read a seat that was already `BOOKED` and the entity guard caught
  it before the lock had to.
- **`cancellationReleasesTheSeatFromTheUniqueIndex`.** Creates a PENDING
  booking, asserts one active claim, cancels, asserts **zero** — and nothing in
  Java set that flag; the trigger `bookings_status_sync_seats` did. Then it
  books the same seat for a different user and asserts one claim again, which is
  the assertion that actually matters: the seat is not merely marked free, it is
  genuinely re-sellable.

### `IntegrationTestBase` and `TestFixtures`

`TestFixtures` builds data with plain SQL rather than through the repositories,
and the reason is the right way round: the domain entities have protected no-arg
constructors and no public setters for venue/seat/event fields, because nothing
in the *application* ever creates a venue or a seat map. Adding public
constructors purely so tests could call them would weaken the production API to
suit the test suite. Writing fixtures in SQL keeps the entities strict and has a
second benefit — the fixture exercises the real schema, including its `CHECK`
constraints.

`reset()` uses `TRUNCATE ... RESTART IDENTITY CASCADE` rather than `DELETE`: far
faster, `CASCADE` follows the foreign keys so the table order does not matter,
and `RESTART IDENTITY` resets the sequences. That last part matters more than it
looks — a test that accidentally depends on "the event with id 1" passes alone
and fails in a suite without it.

`createUser` inserts a pre-computed **cost-4** BCrypt hash rather than calling
the production encoder, because fifty users at cost 12 would spend twelve
seconds on key derivation the test does not care about.

---

## Why the assertion is against the database

```java
assertThat(fixtures.activeClaimsOn(contestedSeat)).isEqualTo(1);
```

where `activeClaimsOn` is literally
`SELECT COUNT(*) FROM booking_seats WHERE event_seat_id = ? AND active`.

The three HTTP assertions above it check what the API *said it did*. This one
checks what is actually true. Those are different questions, and only the second
one is "did we oversell".

An API can report one success while having written two rows — a bug in the
mapper, an exception swallowed after a commit, a response built from a stale
read, a second write on a retry path nobody noticed. Every one of those produces
"one 201 and forty-nine 409s" at the HTTP layer while the table underneath has
two live claims on one chair. Asking the system to grade its own homework cannot
catch that.

The general principle: **assert on the state that the guarantee is a statement
about**, not on the report of the operation that produced it. Here the guarantee
is a sentence about `booking_seats`, so the assertion is a query against
`booking_seats`. The Redis assertion is there for the same reason at the other
layer.

---

## Why `@RepeatedTest`

```java
@RepeatedTest(value = 5, name = "run {currentRepetition} of {totalRepetitions}")
```

A race condition is a *probability*, not a *property*. Whether two threads
interleave badly depends on the OS scheduler, on how many cores are free, on
whether the JIT has warmed up, on GC timing — none of which the test controls.
A bug that manifests one run in twenty is still a bug, and a single green run is
extremely weak evidence about it.

Five repetitions is not proof either — nothing short of a model checker is — but
it turns "it passed" from one sample into five, and it means a genuinely flaky
outcome tends to show up during development rather than in front of an
interviewer. Each repetition gets a fresh `@BeforeEach`: a truncated database, a
flushed Redis, a fresh auditorium and fifty fresh users, so repetitions do not
contaminate each other.

The trade-off is honest: five repetitions of a fifty-request test is 250 HTTP
requests, which is most of the suite's runtime. It is worth it here because this
one test is the project's entire thesis.

---

## Running the suite

**Prerequisite: Docker must be running.** Testcontainers needs a Docker daemon
it can reach; without one, the static initialiser in `IntegrationTestBase`
throws and every integration test errors out before Spring starts.

```bash
# fast half — compiles and runs *Test classes (currently none)
mvn -f backend/pom.xml test

# everything: *Test, then *IT under Failsafe, then the JaCoCo report
mvn -f backend/pom.xml verify

# one class
mvn -f backend/pom.xml verify -Dit.test=ConcurrentSeatBookingIT
```

**Expected runtime**, warm (images already pulled, dependencies already in
`~/.m2`): roughly one to three minutes for `verify`. Where it goes:

- pulling `postgres:16-alpine` and `redis:7-alpine` the first time — a minute or
  two, once, then cached;
- container start plus Flyway on `V1__initial_schema.sql` — a few seconds;
- **two Spring contexts**, because `ConcurrentSeatBookingIT` declares
  `webEnvironment = RANDOM_PORT` while `DefenceInDepthIT` inherits the base
  class's default (`MOCK`). Different context configuration means a different
  cache key, so Spring builds and caches two — a few seconds each;
- the tests themselves: 250 HTTP requests plus five small barrier tests.

**A passing run** ends with Failsafe reporting `Tests run: 6, Failures: 0,
Errors: 0, Skipped: 0` (five repetitions of the contention test count as five,
plus `multiSeatHoldIsAtomic`, plus the three `DefenceInDepthIT` tests — Failsafe
prints them per class), then `BUILD SUCCESS`. JaCoCo writes
`backend/target/site/jacoco/index.html`.

If it fails at `IntegrationTestBase`'s static block with "Could not find a valid
Docker environment", start Docker. If it fails on the Redis key assertion, a
previous run left holds behind — the `FLUSHALL` in `@BeforeEach` normally
prevents this.

---

## What is not tested, honestly

This is the list an interviewer will ask for, so it is better written down than
discovered live. None of the following has a test today:

- **Anything under `security/`.** No test for `JwtService` rejecting
  `alg: none`, for the minimum-key-length startup check, for refresh-token
  rotation, or for `TOKEN_REUSE_DETECTED` revoking a whole chain. These are pure
  unit tests — no container needed — and are the most obvious first `*Test`
  classes to write.
- **Rate limiting.** `application-test.yml` raises the limits to 10 000/min and
  its comment says "the test that actually exercises rate limiting overrides
  these with `@TestPropertySource`". **That test does not exist.** The comment
  describes an intention, not a fact.
- **Idempotency.** The whole of `IdempotencyService` — replay, mismatch,
  in-flight, `releaseClaim` — is untested, despite being the second-most subtle
  piece of the system.
- **The payment paths.** `tok_demo_decline` is never sent. `StubPaymentGateway.
  isOutstanding` exists purely as a test hook — "the booking failed, therefore
  the authorization must have been voided" is exactly the property worth
  asserting — and nothing calls it.
- **The reaper**, including `expireStalePendingClaiming`. `TestFixtures` has
  `backdateHoldExpiry`, `countBookingsWithStatus` and `closeSales` written for
  precisely those tests, and all three are currently unused — scaffolding for
  tests that were planned and not written.
- **`extendHold` and `releaseHold`**, and the `SALES_CLOSED` path.
- **The frontend.** No component tests, no Playwright, no end-to-end. `npm run
  typecheck` and `npm run lint --max-warnings 0` are the only automated gates.
- **A CI pipeline.** Nothing runs any of this automatically on push. See
  `docs/07-deployment.md`.

The two tests that exist are the two that had to exist — they are the ones the
project's central claim rests on. The rest is genuine, and known, debt.
