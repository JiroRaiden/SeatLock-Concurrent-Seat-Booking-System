# INTERVIEW.md — studying and defending SeatLock

A study pack, not a script. The answers below are written the way you would
actually say them out loud. Read them, then close the file and say each one in
your own words — if you can't, go and read the code it points at, because that
means you have memorised a sentence rather than understood a decision.

**The one rule:** never claim something the code does not do. An interviewer who
catches one exaggeration discounts everything else you said. Every "we don't do
this yet, and here's what it would take" in this file is there because saying it
earns more credit than hiding it.

---

## Contents

1. [The numbers to know cold](#1-the-numbers-to-know-cold)
2. [The 60-second pitch](#2-the-60-second-pitch)
3. [The diagram to draw](#3-the-diagram-to-draw)
4. [Tier 1 — concurrency (you *will* be asked these)](#4-tier-1--concurrency)
5. [Tier 2 — system design follow-ups](#5-tier-2--system-design-follow-ups)
6. [Tier 3 — Spring and Java](#6-tier-3--spring-and-java)
7. [Tier 4 — database](#7-tier-4--database)
8. [Tier 5 — security](#8-tier-5--security)
9. [Tier 6 — testing](#9-tier-6--testing)
10. [Curveballs](#10-curveballs)
11. [Weaknesses, and how to own them](#11-weaknesses-and-how-to-own-them)
12. [Questions to ask them](#12-questions-to-ask-them)

---

## 1. The numbers to know cold

| Thing | Value | Why that value |
|---|---|---|
| Hold TTL | **8 minutes** | just above a real card payment with an OTP round trip |
| Hold extension | 3 minutes, effectively once | capped from `createdAt`, so no counter column is needed |
| Max seats per booking | 10 | business rule *and* a bound on the Lua script's key list |
| Access token TTL | 15 minutes | it cannot be revoked, so its lifetime **is** the compromise window |
| Refresh token TTL | 7 days, revocable | stateful, hashed, rotated on every use |
| BCrypt cost | 12 (~250 ms) | slow enough to resist GPU guessing, fast enough not to be a DoS lever |
| Concurrency test | **50 threads, 1 seat, ×5 repeats** | exactly 1 success, 49 clean 409s, 0 oversells |
| Auth rate limit | 10 / min / IP | the endpoint worth brute-forcing |
| Hold rate limit | 30 / min / IP | generous for a real user, tight for a script |
| Browse page queries | **3, regardless of page size** | not 3 + 2N — the N+1 trap |
| Test suite | 71 methods across 11 classes | Surefire (unit) + Failsafe (Testcontainers) |

If you remember nothing else: **8 minutes, 50 threads, three layers.**

---

## 2. The 60-second pitch

Say this when they ask "tell me about a project".

> SeatLock is a concurrent seat-booking system — think BookMyShow. The
> interesting problem isn't the CRUD; it's that when a popular show goes on sale,
> fifty people click the same seat within the same few hundred milliseconds, and
> exactly one of them has to get it.
>
> The naive approach is a database lock, but that fails here for a specific
> reason: the "transaction" includes a human typing card details, so you'd hold a
> Postgres row lock for two minutes and exhaust your connection pool. So the
> reservation has to outlive the database transaction, which means it can't *be*
> one.
>
> What I built instead is three layers. Seat holds live in Redis with an
> eight-minute TTL, acquired by a Lua script so a multi-seat hold is atomic and
> all-or-nothing. That's the fast path and it decides nearly every real conflict.
> But Redis can fail over or restart, so it isn't the guarantee — the guarantee
> is a JPA optimistic lock on the seat row, and beneath that a partial unique
> index in Postgres that makes two live claims on one seat physically impossible
> no matter what the application does.
>
> Redis makes conflicts rare and cheap; the database makes the rare case correct.
> I verified it with a test that fires fifty simultaneous HTTP requests at one
> seat through a `CyclicBarrier` and asserts against the database — not the API's
> own report — that exactly one active claim exists. It's a `@RepeatedTest`,
> because one green run doesn't prove much about a race.

Then stop talking. Let them pick which thread to pull.

---

## 3. The diagram to draw

Practise this until you can draw it in about forty seconds.

```
   Browser (React/TS)
        │
        │  1. GET /events/{id}/seatmap
        │  2. ...user clicks seats — NO network calls, local state only...
        │  3. POST /events/{id}/holds        ← "Proceed"
        │  4. POST /bookings/{id}/confirm    ← "Pay"
        ▼
   ┌──────────────────────────┐
   │  Spring Boot API         │
   │                          │
   │  RateLimitFilter         │
   │  JwtAuthenticationFilter │
   │  BookingService          │────────► Payment provider (authorize / capture)
   └──────┬────────────┬──────┘
          │            │
          ▼            ▼
    ┌──────────┐  ┌──────────────────────────┐
    │  Redis   │  │  Postgres                │
    │          │  │                          │
    │ LAYER 1  │  │  LAYER 2  @Version       │
    │ hold+TTL │  │  LAYER 3  partial UNIQUE │
    │ Lua      │  │           index          │
    └──────────┘  └──────────────────────────┘
      ~1 ms          the durable record
   rare & cheap        correct
```

The two labels at the bottom are the whole argument. Say them as you write them.

---

## 4. Tier 1 — concurrency

### Q: Walk me through what happens when two people click the same seat.

> They don't collide at all when they *click* — seat selection is local state in
> the browser, no network call. That's deliberate: during a drop you'd otherwise
> get thousands of reservations per second that are nearly all abandoned three
> seconds later.
>
> They collide when both press Proceed. That's a `POST /events/{id}/holds`, and
> the first thing it does is generate a UUID and run a Lua script in Redis that
> tries to `SET` a key per seat with an 8-minute TTL. Redis executes scripts
> atomically, so one of the two requests gets all its seats and the other gets
> back the indices of the seats it lost.
>
> The winner then writes a PENDING booking to Postgres. The loser gets a 409 that
> names exactly which seats went, so the UI can grey them out and refetch rather
> than saying "something went wrong".

### Q: Why not just use a database lock?

Rehearse the *duration* argument — that is the answer they're testing for.

> `SELECT ... FOR UPDATE` is correct, and plenty of systems use it. It's wrong
> here because of how long the lock would be held. A pessimistic lock lives until
> the transaction commits, and in this flow what sits between selecting a seat
> and committing is a human typing card details and waiting for an OTP — twenty
> seconds to several minutes.
>
> During that, one connection out of a pool of twenty is parked doing nothing and
> every other reader of that row waits. At a few hundred concurrent checkouts the
> pool is exhausted and the service stops answering *any* request, not just
> contended ones. You'd have turned a one-seat problem into an outage.
>
> And you can't shorten the transaction, because the slow part is a person. So
> the reservation has to outlive the transaction, which means it can't be one.

### Q: Why Redis specifically? Couldn't you use a `HELD` status column?

> You could, and it's the thing I deliberately didn't do — there's no `HELD`
> value in my `SeatStatus` enum, and that absence is the design.
>
> Three reasons. Every seat click becomes a write transaction that's usually
> going to be undone, so you pay WAL, MVCC row versions and vacuum for data
> you're throwing away. Expiry stops being free — something has to notice a hold
> lapsed and reverse it, and if that job is down, seats stay locked forever, so
> now *underselling* is your default failure mode. And the row becomes a
> contention point for everyone reading the seat map.
>
> With Redis, `SET key value PX 480000` deletes itself. The failure mode of my
> cleanup being broken is that nothing happens, because Redis already did it.

### Q: Why is acquiring a Lua script rather than a loop of `SET NX`?

> Because holds have to be all-or-nothing. Four friends want four seats together
> — three of four isn't partial success, it's a failure that has also taken three
> seats off sale for eight minutes.
>
> With a loop, seats one to three succeed, seat four is taken, and now I have to
> undo three writes. In that gap other users see seats as unavailable, and if the
> process dies there they stay locked for the full TTL. `MULTI`/`EXEC` doesn't
> help either — Redis transactions can't branch, so there's no "if any fail,
> apply none".
>
> A Lua script runs to completion with nothing interleaved, so it can do a
> read-only pass, decide, and then a write pass. Bailing out in the first pass
> costs nothing because nothing's been written.

### Q: How do you release a hold?

This is the highest-value answer in the document. Draw the timeline.

> Not with `DEL` — that's the classic distributed-lock bug.
>
> Say Alice holds seat A5. Her TTL fires, Redis frees the key, Bob acquires it.
> Then Alice's "cancel my hold" request, which had been stuck on a bad
> connection, finally lands and runs `DEL A5`. Alice has just released *Bob's*
> lock, and now Carol can acquire it too — two people holding one seat.
>
> So release is a compare-and-delete: only delete if the stored value is still my
> token. And it has to be a Lua script, not Java, because if you do
> `if (get(key).equals(token)) del(key)` the TTL can fire between the two calls
> and you delete someone else's key — the exact bug you were trying to avoid.
>
> The token is the booking's public UUID, so one identifier is both the hold's
> owner token and the booking's public id.

### Q: What does `@Version` actually do?

> Hibernate appends `AND version = ?` to every UPDATE on that table and bumps the
> value, then checks the affected row count. If two transactions both read
> version 3, the first one's UPDATE matches one row and commits; the second
> matches zero rows and Hibernate raises `OptimisticLockException`. I map that to
> a 409 the client can retry.
>
> The important part is that **no lock is ever held**. Nobody waits, nothing
> deadlocks, and a slow client can't block anyone.

### Q: Why optimistic rather than pessimistic there?

> Because of a fact about this system: by the time a request reaches the confirm
> transaction, Redis has already filtered out essentially every competitor. So
> conflicts are rare *by construction*, and optimistic locking is the right fit
> when conflicts are rare — you pay nothing in the common case and retry in the
> rare one.
>
> If conflicts were common, pessimistic would win, because you'd rather wait once
> than retry ten times. That's the actual trade-off.

### Q: What if Redis goes down entirely?

> The site degrades but stays correct. `POST /holds` fails, so nobody can start a
> new checkout — but no oversell is possible because no bookings are being
> created, and confirmed bookings are untouched.
>
> The more interesting case is a **failover to a stale replica**, because Redis
> replication is asynchronous. Then two people genuinely can both acquire the
> same seat and both be told they succeeded. Both go to confirm; the first INSERT
> into `booking_seats` succeeds and the second hits the partial unique index and
> gets rejected. My exception handler maps the constraint violation to the same
> 409 the user would have seen anyway.
>
> So there's no oversell — and that's exactly why I have a test that skips the
> Redis layer entirely, because it's the only way to prove layers two and three
> are actually doing anything.

### Q: So is Redis your source of truth?

> No, and that's the point. Redis is a cache with persistence bolted on — it can
> fail over losing recent writes, or restart empty. Postgres is the source of
> truth. Redis makes conflicts rare and cheap; the database makes the rare case
> correct. They're different mechanisms on purpose, because anything fast enough
> for the hot path can't also be the durable record.

---

## 5. Tier 2 — system design follow-ups

### Q: How would you scale this to a million users?

> A few things, roughly in order of value.
>
> First, honestly, the best answer isn't a faster lock — it's a **virtual waiting
> room** in front of the drop that admits users at a rate the system can serve.
> That's what Ticketmaster and BookMyShow actually do. Making the lock cleverer
> doesn't help if a million people arrive in one second.
>
> Beyond that: Redis Cluster, which my keys are already shaped for — they carry a
> `{evt:N}` hash tag so an event's seats are guaranteed to land on one shard, and
> Cluster rejects multi-key scripts that span shards. That's a config change, not
> a redesign. Read replicas for the seat map, which is the heaviest read and
> tolerates staleness because it's already advisory. Distributed rate limiting,
> because mine is in-JVM today. And ShedLock on the scheduled reaper so only one
> instance runs it.

### Q: What's the biggest weakness in your design?

Do not deflect this. Answering it well is worth more than the design itself.

> The confirm path. There's a window between committing the booking and capturing
> the payment where a crash leaves a confirmed booking that was never charged —
> a free seat.
>
> I put it last deliberately, so the window is one method call wide, and I
> ordered the whole sequence so every *other* failure degrades to "a seat is
> briefly unavailable", which is the right thing to be bad at. I also authorize
> rather than charge, so a crash before the commit just leaves an authorization
> that lapses on its own and the customer never loses money.
>
> The proper fix is a transactional outbox — write "capture this payment" as a
> row in the same transaction as the booking, and have a worker drain it with
> retries. That's the first thing I'd add.

### Q: Why is the seat map allowed to be stale?

> Because it's advisory, not enforcement. It exists to reduce disappointment, not
> to guarantee anything — by the time the response reaches the browser a hold may
> already have expired. Enforcement happens when the user presses Proceed, against
> Redis and Postgres, not against a picture drawn three seconds ago.
>
> If I wanted it live I'd push updates over WebSockets or SSE, but for a screen
> of 200 seats a refetch is fine and one fewer moving part.

### Q: How do you handle a user who closes the tab mid-checkout?

> Nothing happens, and that's the design. The Redis TTL fires after eight minutes
> and the seat is free again — no cleanup code involved.
>
> The database row is tidied separately, by a reaper that flips the PENDING
> booking to EXPIRED. That job is not what releases the seat, which matters: if
> the reaper dies, seats still get released on time and the only symptom is some
> stale rows. There's also an on-demand sweep, because there's a window where
> Redis has freed a seat but the abandoned PENDING row still claims it in
> Postgres — so before creating a hold I expire anything lapsed that's standing
> in the way.

### Q: Two people book different seats for the same show. Do they contend?

> No. The bookable unit is `event_seats` — one row per (event, seat) — so the
> locks are per seat, not per event. There's no shared counter and no row both
> transactions touch. That's a deliberate schema choice: a `seats_remaining`
> column on `events` would serialise the entire show through one hot row.

---

## 6. Tier 3 — Spring and Java

### Q: Why is `BookingPersistence` a separate class from `BookingService`?

> Two reasons, and the first is a real Spring trap. `@Transactional` is
> implemented with a proxy — a wrapper opens the transaction as the call passes
> through it. A call from one method of a class to another method of the *same*
> class never leaves the object, so it never passes through the proxy, so the
> annotation does **nothing at all**, silently. The code looks transactional and
> the logs show no transaction.
>
> Second, it forces the transaction boundary to be a decision. The orchestrator
> talks to Redis and the payment provider; the persistence bean owns the parts
> that must be atomic. Keeping them apart makes it obvious that the network calls
> are *outside* the transaction — which is where they belong, because a
> transaction held open across a third-party HTTP call parks a pooled connection
> for as long as that third party feels like taking.

### Q: What's the N+1 problem and where does it show up here?

> It's when you run one query for a list and then one more per row. My browse
> page shows 20 event cards, each needing its venue, its cheapest price and its
> seat counts — the obvious loop is 1 + 2×20 = 41 queries for one screen.
>
> Instead it's three, regardless of page size: one paged query with the venue
> `JOIN FETCH`ed, one grouped query for all the minimum prices, one grouped query
> for all the counts. Then I join them in memory with two maps.
>
> The reason it's easy to lose is that the N+1 version is shorter, reads
> perfectly well, and behaves fine with six rows of test data.

### Q: Why `open-in-view: false`?

> It's on by default in Spring Boot and it keeps the Hibernate session open
> through view rendering, so lazy associations silently keep working during
> serialisation. That means you can fire database queries from your JSON writer
> without ever noticing — and it holds a connection for the whole request rather
> than just the service call.
>
> Turning it off means a lazy access outside a transaction throws immediately, so
> the N+1s show up in development instead of in production latency graphs.

### Q: Why no Lombok?

> Mostly because I wanted every mechanism visible. `@Version` and the entity
> guards are the interesting part of this codebase, and I didn't want them
> sitting next to annotations that generate code I can't see. Records handle the
> DTOs, which is where the boilerplate actually would have been.

### Q: Why `EnumType.STRING`?

> `ORDINAL` stores the enum's position as an integer, so reordering the constants
> silently rewrites the meaning of every existing row. With a `Role` enum that's
> how a refactor turns every user into an admin.

---

## 7. Tier 4 — database

### Q: Walk me through your schema.

> Physical things and sellable things are separate. A `seat` belongs to a
> `venue` — it's a chair bolted to a floor and exists whether or not anything is
> showing tonight. An `event` is a showing. The bookable unit is `event_seats`,
> one row per (event, seat), because seat A5 at tonight's 7pm and tomorrow's 10am
> have to be sellable independently.
>
> A `booking` has many `booking_seats`, and that table carries the strongest
> guarantee in the system.

### Q: What is that guarantee?

> `CREATE UNIQUE INDEX ... ON booking_seats (event_seat_id) WHERE active`. A
> partial unique index — uniqueness only applies to rows where `active` is true.
> So a seat can appear in many historical bookings but at most one live one, and
> Postgres rejects the second insert regardless of what the application believes.
>
> It's the difference between "we check carefully" and "it cannot happen". Redis
> can be flushed and the version check can be bypassed by a native query someone
> writes in a hurry. That index can't be bypassed by any code path, including a
> manual INSERT typed into psql.

### Q: Why the `active` column instead of joining to booking status?

> A unique index can only see columns of its own table, so a partial index needs
> its predicate locally. `active` is a deliberate small denormalisation of "this
> booking is PENDING or CONFIRMED".
>
> Denormalisation invites drift, so I keep it in step with a database trigger on
> `bookings.status` rather than in Java. There's no correct state where a
> cancelled booking still holds a live seat claim, and an invariant like that
> belongs where no code path — including a manual update at 2am — can forget it.

### Q: Why is money a `BIGINT`?

> Paise, as whole numbers. `0.1 + 0.2 != 0.3` in binary floating point, so
> summing six ticket prices as doubles can give you 2699.9999999999995 and a
> customer who sees a stray paisa on their receipt. `NUMERIC` would also be
> exact; integers are cheaper to index and map straight to a Java `long`. Every
> money field is named `...Minor` so nobody can mistake 45000 for rupees.

### Q: Why `TIMESTAMPTZ`?

> `TIMESTAMP` has no zone, so "19:30" means different things to different
> readers. A ticketing system spans cities — a show at 19:30 IST must not become
> 19:30 UTC. `TIMESTAMPTZ` stores an absolute instant and converts on display.

### Q: Why Flyway rather than `ddl-auto: update`?

> `update` guesses. It'll add a column but won't drop one, won't rename, won't
> write a data migration, and does something different depending on what the
> database already looked like — so two environments drift apart and nobody knows
> when. Flyway migrations are versioned, checksummed, in git, and applied in the
> same order everywhere. I run `ddl-auto: validate`, so Hibernate cross-checks its
> mappings against the real tables at boot and fails loudly if they've diverged.

---

## 8. Tier 5 — security

### Q: How are passwords stored?

> BCrypt at cost 12 — 2^12 rounds, about 250ms. That number is chosen from both
> ends: too low and someone with a leaked table tries billions of candidates a
> second on a GPU; too high and my own login endpoint becomes a DoS lever,
> because each attempt costs me the same CPU it costs them.
>
> BCrypt salts each hash and stores the salt in the output, which is why there's
> no salt column. Argon2id is stronger because it's memory-hard, and my
> `password_hash` column is sized to allow that swap without a migration.
>
> One trap worth knowing: BCrypt only reads the first 72 bytes, so a longer
> password isn't proportionally stronger.

### Q: Why do you store refresh tokens if JWTs are supposed to be stateless?

> Because "stateless" and "revocable" are opposites, and you need both — just not
> in the same token.
>
> The access token is a stateless JWT, 15 minutes, never checked against the
> database, so authenticating a request is one signature verification and zero
> I/O. It can't be revoked, so its lifetime *is* the compromise window.
>
> The refresh token lives 7 days, so it *must* be revocable — logging out or
> changing a password has to actually end the session. That needs state. I store
> a SHA-256 hash of it, never the token, so a leaked backup gives an attacker
> hashes they can't present.

### Q: Why SHA-256 for the refresh token but BCrypt for the password?

They love this one.

> Because they're defending against different things. A password is low-entropy
> and human-chosen, so you need a deliberately slow hash to make guessing
> expensive. A refresh token is 256 bits from a CSPRNG — there's no dictionary to
> try, so the only property I need is preimage resistance, and making it slow
> would just add latency to every refresh.

### Q: What is refresh token rotation?

> Every use of a refresh token consumes it and issues a replacement, so a token
> should be presented exactly once ever. If an already-consumed one turns up
> again, two parties hold it — the real user and a thief — and I can't tell which
> one is in front of me. So I assume the worst and revoke every live token for
> that account. The legitimate user has to log in again, which is mildly annoying
> and much better than an attacker holding a rolling session for a week.

### Q: What are the classic JWT vulnerabilities?

> Two. `alg: none` — early libraries would "verify" a token that claimed no
> algorithm. I use JJWT's `verifyWith(SecretKey)`, which binds verification to
> HMAC at the API level, so an unsigned or asymmetrically-signed token is
> rejected before the claims are read.
>
> And a weak signing key. HS256 with a short secret is brute-forceable offline,
> and then anyone can mint a token claiming admin. My `JwtService` constructor
> refuses to start the application if the key decodes to under 32 bytes — I'd
> rather fail at boot than run with a forgeable key and no symptom.

### Q: How do you prevent IDOR?

> By making it structurally impossible rather than remembering to check. The
> ownership filter is in the repository query itself — `findOwned(publicId,
> userId)` puts the user id in the WHERE clause, so "not found" and "not yours"
> are the same code path and there's no check for a future endpoint to forget.
>
> It also returns 404 rather than 403, deliberately. A 403 confirms a booking
> with that id exists, which lets someone enumerate the id space even though they
> can't read any of it. And booking URLs use a random UUID, not the
> auto-increment id — sequential ids also leak business volume, since you can
> book twice a day and read the growth rate off them.

### Q: Why is CSRF disabled?

> Because CSRF works by the browser attaching cookies to cross-site requests
> automatically. I authenticate with a bearer token, which the browser attaches
> to nothing on its own — a malicious page can make the request but can't add my
> `Authorization` header. No ambient credential, no CSRF.
>
> The moment this moved to cookie auth, CSRF protection would have to come
> straight back on.

### Q: Anything you'd flag about your own security?

> Two things. The frontend keeps the refresh token in `localStorage`, which is
> readable by any XSS. The right answer is an httpOnly cookie, which the split
> S3-plus-EC2 origin setup doesn't get for free — I documented the trade-off
> rather than pretending it's ideal.
>
> And rate limiting is per-JVM, so three instances means three times the
> configured limit. The fix is Bucket4j's Redis backend against the Redis I
> already run. There's also a deployment hazard I wrote up: the load balancer has
> to *overwrite* `X-Forwarded-For` rather than append, or someone can spoof the
> header and get a fresh budget on every request.

---

## 9. Tier 6 — testing

### Q: How do you know the concurrency actually works?

> A test that fires 50 real HTTP requests at one seat simultaneously.
>
> The subtle part is making them *simultaneous*. Submitting 50 tasks to a thread
> pool doesn't do it — the pool starts them as threads free up, and the first
> often finishes before the last has begun, so you get a test that passes without
> ever creating a race. That's the most common way a concurrency test turns out to
> be testing nothing.
>
> So every thread does its setup and then blocks on a `CyclicBarrier`. When the
> fiftieth arrives, the barrier releases them together and they all hit the
> endpoint inside the same few hundred microseconds. The pool has to be sized at
> least 50 or threads that never start leave the barrier unmet and it deadlocks.

### Q: What does it assert?

> Exactly one 201, exactly 49 clean 409s with a specific error code — not 500s,
> because losing a race is a normal outcome and should look like one — and then
> the one that matters: a direct `SELECT COUNT(*)` on `booking_seats` showing one
> active claim. I assert against the database rather than the API's own report,
> because the API telling me it created one booking isn't evidence that it did.
>
> It's a `@RepeatedTest(5)`, since a race that fails one time in twenty is still
> a race.

### Q: Why Testcontainers instead of H2?

> Because the things I'm claiming are database behaviours, and a substitute
> doesn't have them. H2 can't create a partial unique index, so my strongest
> guarantee wouldn't exist under test — a suite that passes without the constraint
> it's meant to prove is worse than no suite. H2's concurrency model isn't
> Postgres's either, so a green concurrency test would mean nothing. And embedded
> Redis substitutes have historically stubbed `EVAL`, and my whole correctness
> argument rests on real Lua atomicity.
>
> Bonus: running the real migrations against the real engine puts the migrations
> themselves under test.

### Q: Which of your tests is most valuable?

Good question to have a real opinion on.

> `DefenceInDepthIT`, and it's not the obvious one. The main concurrency test
> runs with Redis healthy, so Redis catches every conflict and layers two and
> three are never exercised — a green run would look identical whether the
> `@Version` column and the unique index were working or quietly broken.
>
> So that test skips the hold layer entirely and attacks the database directly
> with two concurrent writers, which is exactly the state a Redis failover
> produces. It's the only test that proves the safety net exists.

---

## 10. Curveballs

**"Isn't three layers over-engineering?"**
> It'd be a fair challenge if they were three attempts at the same thing. They're
> not — each survives a different failure. Drop Redis and the database becomes a
> contention point on the hot path. Drop `@Version` and a Redis failover
> oversells. Drop the index and any future bug oversells. The one I'd defend
> hardest is the index, because it costs nothing and it's the only one that
> survives *me* being wrong.

**"What if two people book the last seat at exactly the same nanosecond?"**
> There's no such thing as exactly the same nanosecond from the system's point of
> view — Redis executes commands one at a time on a single thread, so one of them
> is first. That's the whole reason the arbitration lives somewhere serialised
> rather than in application code.

**"Your test only proves it works on your machine."**
> Fair, and that's why the assertions are against the database rather than the
> API, and why it's repeated. It runs in CI on GitHub's runners too, which are
> slower and differently loaded — different timing, same result. What it doesn't
> prove is behaviour across multiple API instances; for that I'd need a load test
> against a real deployment, and I'd use k6 or Gatling.

**"Why not Kafka / event sourcing / CQRS?"**
> Because the problem doesn't call for them. This is a strongly-consistent
> exactly-once decision on a single row, which is precisely what a relational
> database is best at. An event-sourced version would need a way to reject a
> conflicting command, and I'd end up reinventing a unique constraint. I'd reach
> for Kafka where I actually have one — a transactional outbox for the payment
> capture gap.

**"How long did this take?"**
> Answer honestly, and pivot to what took the time. "Most of it went on the
> failure analysis — deciding which failures to accept, not writing the happy
> path" is a strong answer.

**"Did you use AI to build this?"**
> Be straightforward. Something like: *"Yes, as a pair — I made the design calls
> and I can defend each one, which is what this document is."* Then invite them
> to test it: *"Ask me why any line is the way it is."* Confidence here reads far
> better than either denial or apology, and it is a claim you can back up if you
> have actually read the code.

---

## 11. Weaknesses, and how to own them

Have these ready. Volunteering a limitation before they find it makes everything
else you said more credible.

| Weakness | How to say it |
|---|---|
| Confirm/capture gap | "Known window, placed last on purpose so it's one call wide. Fix is a transactional outbox — first thing I'd add." |
| Payment is a stub | "Deliberate. It's an interface with a deterministic fake, which is what lets me test the declined path — that's usually the one that's broken." |
| Rate limiting is per-instance | "In-JVM, so N instances means N× the limit. Bucket4j's Redis backend against the Redis I already run." |
| Refresh token in `localStorage` | "XSS can read it. httpOnly cookie is right; split-origin deployment doesn't give it for free. Documented rather than hidden." |
| Reaper runs on every instance | "Wasteful, not wrong — each expiry re-checks its own state. ShedLock is the fix." |
| Search is a `LIKE '%q%'` scan | "Can't use a B-tree with a leading wildcard. Fine at six events; at 100k it's a Postgres full-text index or a search engine. Premature now." |
| No load test | "I've proven correctness under contention, not throughput. I'd want k6 against a real deployment before claiming a number." |
| Single region | "No multi-region story. Redis Cluster and RDS read replicas first; cross-region needs a conversation about what consistency you're willing to give up." |

---

## 12. Questions to ask them

Ask about the thing you just spent a week on. It reads as genuine because it is.

- "How do you handle the equivalent of this — where two requests race for one
  resource? Do you use optimistic locking, or something else?"
- "When you get a conflict like that in production, does the client retry
  automatically, or do you surface it to the user?"
- "Do you run integration tests against real infrastructure, or mocks? I went the
  Testcontainers route and I'm curious where that lands at your scale."
- "What's your split between correctness you enforce in the application versus in
  the database? I ended up pushing more into constraints than I expected to."

---

**Study order:** [`00-overview.md`](00-overview.md) →
[`03-concurrency.md`](03-concurrency.md) (the important one) →
[`04-booking-lifecycle.md`](04-booking-lifecycle.md) →
[`02-security.md`](02-security.md) → [`05-testing.md`](05-testing.md) → this file.
