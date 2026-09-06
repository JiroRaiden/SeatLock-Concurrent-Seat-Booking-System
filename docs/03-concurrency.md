# 03 — Concurrency: how exactly one person gets the seat

This is the document the whole project exists for. Everything else — the auth,
the React app, the CI pipeline — is scaffolding around the problem described
here.

If you read one file before an interview, read this one.

---

## 1. The problem, stated precisely

A popular show goes on sale at 10:00:00. At 10:00:00.000, several hundred people
are looking at the same seat map. Seat H12 is the best seat in the house, and
fifty of them click it within the same few hundred milliseconds.

The system must guarantee:

> **Exactly one of those fifty ends up owning seat H12. The other forty-nine get
> a clear, immediate "that seat has gone" — not an error, not a spinner, and
> definitely not a confirmation email for a seat somebody else is sitting in.**

Two failure modes are unacceptable, and they fail in opposite directions:

- **Overselling** — two people hold tickets for one seat. Discovered at the door,
  by a human, in front of a queue. This is a business and reputational failure,
  not a technical inconvenience.
- **Underselling** — a seat is locked by a process that crashed, or by a shopper
  who wandered off, and nobody can buy it. On a 200-seat screen with a 90%
  cart-abandonment rate during a hot drop, careless locking can make a show look
  sold out while three-quarters of it is empty.

A third requirement is easy to forget and is what rules out most simple designs:

- **It must stay fast.** Whatever we do runs on the hot path of a traffic spike.
  A correct design that adds 200ms to every seat click is not a correct design
  for this problem.

---

## 2. Why the obvious solutions do not work

It is worth walking these, because an interviewer will propose at least one of
them and expect you to know why it fails.

### 2.1 "Check if it's free, then book it"

```java
if (seat.getStatus() == AVAILABLE) {   // (1) read
    seat.setStatus(BOOKED);            // (2) write
    repository.save(seat);
}
```

This is the **check-then-act race**, and it is the bug at the heart of the whole
problem. Two threads both execute (1), both see `AVAILABLE`, and both proceed to
(2). The window between the read and the write is small, but "small" is not
"zero", and at fifty concurrent requests it is hit constantly.

No amount of care inside this shape fixes it. The check and the act have to
become a single indivisible operation, or something else has to arbitrate.

### 2.2 `synchronized`, or a `ReentrantLock`

```java
synchronized (seatLockFor(seatId)) { ... }
```

This genuinely works — on **one JVM**. The moment you run two instances behind a
load balancer, each has its own set of locks and neither knows about the other's.
Two requests routed to two instances see two different locks and both proceed.

Worse, it looks like it works. It passes every test on a developer's laptop, and
fails the first time the service is scaled out — which is exactly when traffic is
high enough to matter.

> **The rule:** a lock is only a lock if every contender goes through the same
> instance of it. In a horizontally-scaled system, that means the lock has to
> live outside the application process.

### 2.3 `SELECT ... FOR UPDATE` — a pessimistic database lock

```sql
SELECT * FROM event_seats WHERE id = 91 FOR UPDATE;
```

This is correct. Postgres takes a row lock, the second transaction blocks until
the first commits, and there is no race. Many systems do exactly this and are
fine.

It is wrong **here**, and the reason is duration.

A pessimistic lock is held until the transaction ends. In a booking flow, what
happens between "user picks a seat" and "payment completes" is:

```
  select seat  →  show checkout  →  human types card details  →
  bank OTP round trip  →  payment confirms  →  commit
```

That is anywhere from twenty seconds to several minutes of **human time** inside
the lock. During it:

- one connection from a pool of twenty is parked, doing nothing;
- every other reader of that row waits;
- at a few hundred concurrent checkouts the pool is exhausted and the service
  stops answering *any* request, not just contended ones.

You have converted a problem affecting one seat into an outage affecting
everybody. And you cannot fix it by shortening the transaction, because the slow
part is a person.

> **The insight this project is built on:** the reservation has to outlive the
> database transaction. So it cannot *be* a database transaction.

### 2.4 A `status = 'HELD'` column

Tempting, and it is why `SeatStatus` deliberately has no `HELD` value. If a hold
is a row update:

- every seat click becomes a write transaction, and during a drop nearly all of
  them will be undone seconds later — you are paying WAL, MVCC row versions, and
  later vacuum, for data you are about to throw away;
- expiry is not free. Something has to notice a hold has lapsed and reverse it.
  If that job is down, seats stay locked forever, and *underselling* is now your
  default failure mode;
- the hot row becomes a contention point for readers of the seat map.

Holds are ephemeral, high-frequency, and self-expiring. That is not what a
durable, transactional, ACID row is for.

---

## 3. The design: three layers, on purpose

No single mechanism is both **fast enough for the hot path** and **durable enough
to be the system of record**. So SeatLock uses three, each doing the job it is
actually good at.

```
                      ┌──────────────────────────────────────┐
   POST /holds ──────►│  LAYER 1 — Redis hold, TTL 8 min     │
                      │  atomic Lua, all-or-nothing          │  ~1 ms
                      │  decides ~100% of real conflicts     │
                      └───────────────┬──────────────────────┘
                                      │ acquired
                                      ▼
                      ┌──────────────────────────────────────┐
   POST /confirm ────►│  LAYER 2 — JPA @Version optimistic   │
                      │  lock on event_seats                 │  ~3 ms
                      │  catches what Redis missed           │
                      └───────────────┬──────────────────────┘
                                      │ committed
                                      ▼
                      ┌──────────────────────────────────────┐
                      │  LAYER 3 — Postgres partial UNIQUE   │
                      │  index on booking_seats              │  free
                      │  cannot be bypassed by any code      │
                      └──────────────────────────────────────┘
```

Read the layers as answering three different questions:

| Layer | Question it answers | Failure it survives |
|---|---|---|
| 1. Redis hold | Who got here first? | — |
| 2. `@Version` | Did the state change under me? | Redis down, failed over, or flushed |
| 3. Unique index | Is this physically possible? | Any application bug, including a future one |

Layer 1 makes conflicts **rare and cheap**. Layers 2 and 3 make the rare case
**correct**. That division is the entire design.

---

## 4. Layer 1 — the Redis hold

**Code:** `hold/SeatHoldService.java`, `resources/redis/*.lua`

### 4.1 Why Redis

- **Expiry is a first-class feature.** `SET key value PX 480000` and the key
  deletes itself in eight minutes. No sweeper, no status column, no "what if the
  cleanup job is down". The failure mode of our cleanup being broken is
  *nothing happens*, because Redis already did it.
- **Single-threaded command execution** means atomicity is cheap. Redis does not
  need locks to be atomic; it simply does one thing at a time.
- **It is the right cost for the operation.** A hold is a write we expect to
  discard. Doing that in memory is roughly two orders of magnitude cheaper than
  doing it in Postgres.

### 4.2 The key, and the braces in it

```
seatlock:hold:{evt:42}:9137
└───┬────┘ └──┬───┘ └─┬─┘
  namespace  hash tag  event_seats.id
```

The `{evt:42}` braces are a **Redis Cluster hash tag**. Cluster hashes only the
text inside braces when choosing a shard, so every seat of event 42 lands on one
node.

That matters because our Lua scripts touch many keys at once, and Redis Cluster
rejects a multi-key command whose keys span shards. Writing the key this way on a
single node costs nothing today and means moving to Cluster later is a config
change rather than a redesign.

### 4.3 Why the acquire is a Lua script

The requirement is **all-or-nothing**. Four friends want four seats together;
three of four is not a partial success, it is a failure that has also taken three
seats off sale for the next eight minutes.

The loop version fails:

```
for each seat:  SET seat NX PX 480000      ← WRONG
```

Seats 1–3 succeed, seat 4 is taken, and now you must undo 1–3. Between the
failure and the cleanup, other users see three seats as unavailable. If the
process dies in that gap, they stay locked for the full TTL.

`MULTI`/`EXEC` does not help either. Redis transactions queue commands and run
them together, but they **cannot branch** — there is no way to say "if any of
these fail, apply none of them". By the time you can see a result, `EXEC` has
already committed.

A Lua script does work, because Redis runs a script to completion with no other
client's command interleaved. So it can safely do a **read pass**, decide, and
then a **write pass**:

```lua
-- Pass 1: read-only. Nothing is written, so bailing out here is free.
for i = 1, #KEYS do
    local holder = redis.call('GET', KEYS[i])
    if holder and holder ~= token then
        conflicts[#conflicts + 1] = i
    end
end
if #conflicts > 0 then return conflicts end

-- Pass 2: every seat was free (or already ours). Claim them all.
for i = 1, #KEYS do
    redis.call('SET', KEYS[i], token, 'PX', ttl_ms)
end
return {}
```

Two details worth noticing:

- It returns the **indices of the conflicting keys**, so the API can tell the
  user exactly which seats they lost rather than a vague "try again".
- It compares `holder ~= token` rather than using `SET NX`. This makes a retry
  with the *same* token re-acquire its own seats and refresh the TTL. Network
  retries are normal; a retry must not look like contention.

### 4.4 The fencing token — the detail that separates a working lock from a broken one

**Releasing a hold is not `DEL`.** This is the single most commonly-botched part
of any distributed lock, and it is worth being able to draw on a whiteboard:

```
 t=0      Alice acquires seat A5.  key A5 = "alice-token",  TTL 8m
 t=8m00s  TTL fires. Redis deletes the key. A5 is free.
 t=8m01s  Bob acquires A5.         key A5 = "bob-token"
 t=8m02s  Alice's "cancel my hold" request, stuck on a bad connection,
          finally lands and runs:  DEL A5
          ────────────────────────────────────────────────
          Alice has just released BOB's lock.
 t=8m03s  Carol acquires A5. Bob and Carol both believe they hold it.
```

The fix is a compare-and-delete, which must itself be atomic:

```lua
for i = 1, #KEYS do
    if redis.call('GET', KEYS[i]) == token then
        redis.call('DEL', KEYS[i])
    end
end
```

Note this **cannot** be done in Java:

```java
if (redis.get(key).equals(token)) redis.del(key);   // ← still broken
```

Between the `GET` and the `DEL`, the TTL can fire and someone else can acquire —
and you delete their key. The check and the delete must be one step, which means
a script.

The token here is the pending booking's public UUID. One identifier serves as
both the reservation's owner token and the booking's public id, so there is no
mapping table between them and no way for the two to drift apart.

### 4.5 What Redis is *not*

Redis is a cache with persistence bolted on. It can:

- fail over to a replica that is missing the last few writes (its replication is
  asynchronous — the primary acknowledges your write before the replica has it);
- be restarted with an empty dataset, releasing every live hold at once;
- be flushed by an operator at the wrong moment.

In any of those windows, **two users can both hold seat A5 and both be told they
succeeded**. Redis alone cannot be the guarantee. That is not a criticism of
Redis; it is what you get in exchange for the speed, and the design accounts for
it rather than hoping.

> If an interviewer asks *"is Redlock safe?"* — the honest answer is that this
> system does not rely on Redis for correctness at all, so the question does not
> arise. Correctness lives in Postgres. Redis is an optimisation that keeps
> conflicts away from it.

---

## 5. Layer 2 — optimistic locking with `@Version`

**Code:** `domain/EventSeat.java`, `booking/BookingPersistence.confirmBooking`

One annotation:

```java
@Version
@Column(nullable = false)
private Long version;
```

With it, Hibernate rewrites every `UPDATE` to that table:

```sql
UPDATE event_seats
   SET status = 'BOOKED', version = 4
 WHERE id = 91
   AND version = 3;        -- ← Hibernate adds this
```

and then checks the affected row count. So when two transactions both read
version 3:

```
 T1: UPDATE ... WHERE id=91 AND version=3   →  1 row   → commits, version is 4
 T2: UPDATE ... WHERE id=91 AND version=3   →  0 rows  → OptimisticLockException
```

T2 loses cleanly. We translate that into a `409 CONCURRENT_MODIFICATION`, which
the client may safely retry.

### 5.1 Optimistic vs pessimistic — the actual trade-off

| | Pessimistic (`FOR UPDATE`) | Optimistic (`@Version`) |
|---|---|---|
| Mechanism | take a lock, make others wait | detect the collision at write time |
| Cost when uncontended | a lock acquisition every time | nothing |
| Cost when contended | waiting; possible deadlocks | a failed transaction to retry |
| Best when | conflicts are common | conflicts are rare |

The choice follows from a fact about *this* system: **by the time a request
reaches the confirm transaction, Redis has already filtered out essentially every
competitor.** Conflicts here are rare by construction. Optimistic locking is
therefore the right fit — no waiting, no lock ordering to get right, no
deadlocks.

The transaction is also genuinely short now. Everything slow — the human, the
payment provider — happens *outside* it, which is exactly what the Redis hold
bought us.

### 5.2 Where the version check is not enough

Bulk JPQL updates bypass Hibernate's version handling entirely. That is why
`BookingRepository.expireStalePendingClaiming` increments the column by hand:

```sql
SET b.status = EXPIRED, b.version = b.version + 1
```

Without it, a sweeper could silently overwrite a confirmation that was in flight.

---

## 6. Layer 3 — the constraint that cannot be bypassed

**Code:** `V1__initial_schema.sql`, `domain/BookingSeat.java`

```sql
CREATE UNIQUE INDEX booking_seats_one_active_per_seat
    ON booking_seats (event_seat_id) WHERE active;
```

A **partial unique index**: uniqueness applies only to rows where `active` is
true. So a seat may appear in many historical bookings (cancelled, expired) but
in at most **one** live booking.

This is the difference between *"we check carefully"* and *"it cannot happen"*.
Redis can be flushed. The `@Version` check can be bypassed by a native query
written in a hurry by a future contributor. This index cannot be bypassed by any
code path at all — including a manual `INSERT` typed into `psql` at 2am.

### 6.1 Why the `active` column exists

A unique index can only see columns of its own table, so the partial predicate
has to be local. `active` is therefore a deliberate, tiny denormalisation of
"this booking is PENDING or CONFIRMED".

Denormalisation invites drift, so the invariant is enforced in the database, not
in Java:

```sql
CREATE TRIGGER bookings_status_sync_seats
    AFTER UPDATE OF status ON bookings
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status)
    EXECUTE FUNCTION sync_booking_seat_active();
```

There is no correct system state in which a CANCELLED booking still holds an
active seat claim. Invariants belong as close to the data as possible, where no
code path can forget them.

### 6.2 A consequence worth knowing

Because a **PENDING** booking already writes `booking_seats` rows with
`active = true`, the database claims the seat before payment. That is deliberate
— it means Postgres, not Redis, is authoritative even during a hold.

It also creates one window, which the code closes explicitly:

```
 t=8m00s  Redis TTL fires. Seat free as far as Redis is concerned.
 t=8m01s  Bob's Redis hold succeeds...
          ...but his INSERT hits the unique index, because Alice's abandoned
          PENDING row is still active.
 t=8m45s  The background reaper finally expires Alice's booking.
```

For those 45 seconds the seat looks taken but belongs to nobody. Running the
sweeper more often only shortens the window; it cannot close it. So
`createPendingBooking` also sweeps **on demand**:

```java
bookings.expireStalePendingClaiming(seatIds, Instant.now());
```

Anything already lapsed and standing in the way is expired first. The
user-visible window becomes zero, and the background reaper stays only for seats
nobody happens to be asking for.

---

## 7. The two-store ordering problem

Redis cannot join a Postgres transaction. The payment provider certainly cannot.
There is no transaction spanning all three, and pretending otherwise is how money
and seats go missing.

What you *can* do is **choose the order of operations so that every failure point
leaves a state you can recover from.**

```
HOLD      1. read availability from Postgres     (cheap, may be stale)
          2. acquire the hold in Redis           (the real gate)
          3. write the PENDING booking           (durable claim)
          4. on failure of 3 → release 2

CONFIRM   1. verify we still own the Redis hold
          2. AUTHORIZE payment  (reserve, do not take)
          3. commit the booking                  ← point of no return
          4. CAPTURE payment
          5. release the Redis hold
          6. on failure of 3 → VOID the authorization
```

Now walk every crash point:

| Crash between | Surviving state | Severity |
|---|---|---|
| hold 2 and 3 | a Redis hold with no booking | expires in 8 min. Harmless. |
| confirm 2 and 3 | money authorized, not captured, no booking | the authorization lapses on its own. Customer sees a pending hold vanish. This is *why* we authorize rather than charge. |
| confirm 3 and 4 | booking confirmed, money never captured | **a free seat.** The one genuinely bad outcome. |
| confirm 4 and 5 | Redis hold outlives its booking | expires by itself; the seat is `BOOKED` anyway. Harmless. |

Every path but one degrades to *"a seat is briefly unavailable"* — which is the
correct thing to be bad at. The remaining one is placed **last**, so the window is
one method call wide, and it is called out here rather than hidden. A production
system reconciles it with a transactional outbox or a nightly settlement job
against the payment provider.

> "Which failure did you choose to accept?" is the question that separates
> someone who has thought about distributed systems from someone who has read
> about them. Have an answer.

---

## 8. Failure analysis: what if Redis dies?

Worth rehearsing, because it is the most likely follow-up question.

**Redis is completely down.** `SeatHoldService` throws on every call, so
`POST /holds` fails and nobody can start a checkout. The site is degraded but
**correct** — no oversell is possible because no new bookings are being created.
Already-confirmed bookings are untouched.

**Redis failed over to a stale replica.** Some holds are missing, so two users
can both acquire the same seat. Both proceed to confirm. Now:

- the first `INSERT` into `booking_seats` succeeds;
- the second hits `booking_seats_one_active_per_seat` and is rejected;
- `GlobalExceptionHandler` maps the `DataIntegrityViolationException` to a
  `409 SEAT_UNAVAILABLE`.

The user sees exactly the same message they would have seen if Redis had caught
it. **No oversell.** This is precisely the scenario `DefenceInDepthIT` simulates
by skipping the hold layer entirely.

**Redis restarted empty.** Every live hold vanishes at once. Seats become
immediately re-claimable in Redis, but the PENDING `booking_seats` rows still
claim them in Postgres, so the second claimant gets a clean 409 until those rows
lapse. Ugly for a few minutes; still not an oversell.

**The application's reaper is dead.** Seats are still released on time, because
Redis TTLs are not our code. Only the database tidying stops, leaving stale
PENDING rows — and the on-demand sweep in §6.2 cleans those the moment somebody
tries to book the seat.

There is no failure in this list that sells a seat twice. That is the point of
having three layers.

---

## 9. How we know it actually works

**Code:** `ConcurrentSeatBookingIT`, `DefenceInDepthIT`

A concurrency test that does not create a race proves nothing, and most of them
do not. Submitting fifty tasks to a thread pool does **not** make them
simultaneous — the pool starts them as threads free up, and the first often
finishes before the last has begun.

The fix is a `CyclicBarrier`:

```java
CyclicBarrier startLine = new CyclicBarrier(50);
...
startLine.await();                      // nobody moves until all 50 arrive
http.send(request, ...);                // then they all go at once
```

Every thread does its setup, then blocks. When the fiftieth arrives the barrier
releases them together and they hit the endpoint within the same few hundred
microseconds. (The pool must be sized ≥ 50, or threads that never start will
leave the barrier permanently unmet and the test deadlocks — itself a useful
thing to understand.)

Then the assertions, in order of what they actually prove:

1. exactly one `201`;
2. exactly forty-nine `409 SEAT_UNAVAILABLE` — clean rejections, not 500s;
3. **`SELECT COUNT(*) FROM booking_seats WHERE event_seat_id = ? AND active` is
   1** — reading the database directly, because the API's own account of what it
   did is not evidence;
4. exactly one hold key in Redis.

Run as `@RepeatedTest(5)`, because a race that fails one time in twenty is still
a race and one green run proves very little.

`DefenceInDepthIT` matters more than it looks: the main test runs with Redis
healthy, so Redis catches every conflict and layers 2 and 3 are never exercised.
A green run there would look identical whether `@Version` and the unique index
were working or quietly broken. So that test bypasses Redis entirely and attacks
the database directly — which is exactly the state a Redis failure produces.

---

## 10. What would change at 100× the scale

Worth having ready, because "how would you scale this?" always comes.

**Redis Cluster.** The keys already carry `{evt:N}` hash tags, so an event's
seats are guaranteed to live on one shard and the Lua scripts keep working.
This is a configuration change, not a redesign.

**Distributed rate limiting.** The current token buckets are in-JVM, so N
instances mean N times the configured limit. The fix is Bucket4j's Lettuce-backed
proxy manager against the Redis we already run — same `Bandwidth` definitions,
different storage.

**ShedLock on the reaper.** Every instance currently runs the sweep. It is
harmless (each expiry re-checks its own state) but wasteful; ShedLock uses a
database row as a mutex so exactly one instance runs each scheduled task.

**A transactional outbox** for the confirm/capture gap in §7 — the single
correctness improvement worth making first.

**Read replicas** for the seat map, which is by far the heaviest read. It
tolerates staleness perfectly well; it is already advisory.

**A queue in front of the drop.** Above a certain scale the honest answer is not
to make the lock faster but to stop the stampede reaching it — a virtual waiting
room that admits users at a rate the system can serve. This is what Ticketmaster
and BookMyShow actually do, and saying so is a better answer than proposing an
ever-cleverer lock.

---

## 11. The thirty-second version

> Seat holds live in Redis with an eight-minute TTL, acquired by a Lua script so
> that a multi-seat hold is atomic and all-or-nothing, and released by
> compare-and-delete against an ownership token so a late release can never free
> somebody else's seat. That is the fast path, and it decides essentially every
> real conflict.
>
> But Redis can fail over or restart, so it is not the guarantee. The guarantee
> is in Postgres: a JPA `@Version` optimistic lock on the seat row, and beneath
> that a partial unique index that makes two live claims on one seat physically
> impossible regardless of what the application does.
>
> Redis makes conflicts rare and cheap. The database makes the rare case correct.
> They are different mechanisms on purpose, because anything fast enough for the
> hot path cannot also be the durable record.
>
> It is verified by a test that fires fifty simultaneous HTTP requests at one
> seat through a `CyclicBarrier` and asserts, against the database rather than
> the API, that exactly one active claim exists — repeated five times per run.

---

**Next:** [`04-booking-lifecycle.md`](04-booking-lifecycle.md) for the full state
machine and the idempotency protocol · [`05-testing.md`](05-testing.md) for how
the suite is structured · [`INTERVIEW.md`](INTERVIEW.md) for the questions this
document is the answer to.
