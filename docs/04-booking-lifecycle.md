# The booking lifecycle

This document is about **ordering**. Three systems have to agree on whether a
seat is sold — Redis, Postgres and a payment provider — and there is no
transaction that spans them. Redis cannot join a Postgres transaction; the
payment provider certainly cannot. So the flow cannot be made atomic, and
pretending otherwise is how money and seats go missing.

What *can* be done is choose the order of operations so that **every possible
crash point leaves a state that is recoverable**, and then be honest about the
one point where it is not. That is the whole design principle of
`booking/BookingService.java`, and it is stated in that class's own javadoc.

Read `docs/03-concurrency.md` first for how a hold is acquired. This document
picks up from "the hold exists" and follows it to a paid booking.

---

## The state machine

`domain/BookingStatus.java` has four values and three transitions. There is no
transition back out of a terminal state.

```
                    POST /events/{id}/holds
                    Redis hold acquired, then booking row written
                                 |
                                 v
                        +-----------------+
                        |     PENDING     |
                        +-----------------+
                        booking_seats.active = TRUE   <- claims the seat
                        event_seats.status   = AVAILABLE  (unchanged!)
                        expires_at           = now + 8m
                        Redis key            = live, PX 480000
                                 |
            +--------------------+---------------------+
            |                    |                     |
   confirm  |           cancel   |          TTL lapsed |  + reaper
   (paid)   |         (user or   |         (user walked away)
            |          Back btn) |                     |
            v                    v                     v
    +---------------+    +---------------+    +---------------+
    |   CONFIRMED   |    |   CANCELLED   |    |    EXPIRED    |
    +---------------+    +---------------+    +---------------+
    active   = TRUE      active   = FALSE     active   = FALSE
    status   = BOOKED    (trigger)            (trigger)
    expires_at = NULL    seat back on sale    seat back on sale
    Redis key released
            |
            | cancel before showtime
            v
    +---------------+
    |   CANCELLED   |   active = FALSE (trigger), event_seats -> AVAILABLE
    +---------------+
```

Two things in that diagram are easy to skim past and are the heart of the
design.

**A PENDING booking does not change `event_seats.status`.** The seat is still
`AVAILABLE` in the seat-status column while somebody is paying for it. What
claims the seat in Postgres is the `booking_seats` row itself, through the
partial unique index `booking_seats_one_active_per_seat`. `BookingPersistence.
createPendingBooking` says so explicitly and does not call `markBooked()`. The
seat's *status* only changes when money has actually been reserved.

**Nothing in Java sets `booking_seats.active = FALSE`.** The database trigger
`bookings_status_sync_seats` does it, on any UPDATE that moves `bookings.status`
into `CANCELLED` or `EXPIRED`. `BookingPersistence.cancelBooking` never touches
the flag, and `DefenceInDepthIT.cancellationReleasesTheSeatFromTheUniqueIndex`
asserts the trigger did the work. See `docs/01-data-model.md` for why that
invariant lives in the database.

`isTerminal()` returns true for `CANCELLED`, `EXPIRED` **and** `CONFIRMED`, and
`holdsSeats()` returns true for `PENDING` and `CONFIRMED` — those are the two
states in which a `booking_seats` row is `active`.

---

## Creating a hold, step by step

`BookingService.createHold` is **deliberately not `@Transactional`**. If it
were, the Redis round trip and the availability read would sit inside one long
database transaction, holding one of twenty pooled connections (`DB_POOL_SIZE`,
`application.yml`) while we talk to a different server. Only the Postgres write
needs to be atomic, and that happens inside `BookingPersistence`, a different
bean and therefore a real proxy boundary.

The order is exact and each step exists for a reason.

**0. Validate.** `requestedSeatIds` is de-duplicated and sorted. Empty or more
than `holdProperties.getMaxSeatsPerBooking()` (10) is a `VALIDATION_FAILED`
400. Then `events.findWithVenue(eventId)`, then `event.isOpenForSale(now)` —
server side, always, even though the frontend hides the button. The cap is
enforced twice on purpose: `CreateHoldRequest` carries `@Size(max = 10)` for a
clean 400 before any of our code runs, and the service re-checks the
*configured* value.

**1. Read availability from Postgres.** `eventSeats.findForEvent(eventId,
seatIds)` — note `eventId` is in the WHERE clause, which makes it an
authorisation check disguised as a filter: a request against event 7 cannot
reach a seat belonging to event 8 by passing its id. If fewer rows come back
than ids were sent, the missing ones are reported as `SEAT_UNAVAILABLE`, not
`NOT_FOUND`, because two different answers would let a caller map out which seat
ids exist. If any returned seat is not `AVAILABLE`, bail out early.

This read is **already stale by the time we act on it** and the code says so.
It is a cheap early exit for the common case of clicking a seat that was sold an
hour ago; it saves a Redis round trip. It is not the gate.

**2. Acquire the hold in Redis.** `UUID holdId = UUID.randomUUID()` is generated
here, *before* anything durable is written, because it is three things at once:
the Redis ownership token, the booking's `public_id`, and the `holdId` in the
API response. One identifier means there is no mapping table between "the
reservation" and "the pending booking" and no way for the two to drift.
`holds.acquire(eventId, seatIds, holdId, ttl)` runs `acquire_holds.lua`, which
is all-or-nothing. A rejection returns `409 SEAT_UNAVAILABLE` with
`details.unavailableSeatIds`. **This is the gate.**

**3. Persist the PENDING booking.** `users.getReferenceById(caller.id())` gets a
lazy proxy rather than a SELECT — we only need the foreign key. Then
`persistence.createPendingBooking(...)`, which in its own transaction:

- runs `bookings.expireStalePendingClaiming(seatIds, now)` first (see the reaper
  section below);
- **re-reads seat availability inside the transaction**, because the caller's
  read in step 1 was in a different transaction and is milliseconds old — this
  is the check that counts;
- builds the `Booking` and its `BookingSeat` lines and `save()`s, cascading to
  the seat rows. If two requests somehow reach here for the same seat, the
  partial unique index rejects the second with a
  `DataIntegrityViolationException`, which `GlobalExceptionHandler` renders as
  a 409. That is the database having the last word.

**4. Compensate if step 3 failed.** The `catch (RuntimeException ex)` block
calls `holds.release(eventId, seatIds, holdId)` and rethrows. Without it, a
database failure would leave the seats locked in Redis for the full eight
minutes for a booking that does not exist. `release_holds.lua` is a
**compare-and-delete on our own token**, so even in a confusing state it cannot
delete somebody else's hold — which is precisely why a cleanup path can be
called from a `catch` block safely. That property is the single most commonly
botched detail in distributed-lock code and is explained in full in
`release_holds.lua` itself.

The response is `201` with `holdId`, `expiresAt`, `ttlSeconds: 480`,
`totalMinor` and the seat lines sorted by row and seat number.

Two smaller operations sit alongside:

- **`extendHold`** (`POST /holds/{holdId}/extend`) grants one 3-minute
  extension. It extends *both* stores — Redis via `extend_holds.lua`, and the
  `expires_at` column via `setExpiresAtForExtension` so a page refresh draws the
  right countdown. If `holds.extend` returns false, one seat has been lost and
  the whole hold is dead: extending the rest would keep seats out of sale for a
  booking that can never complete.
- **`releaseHold`** (`DELETE /holds/{holdId}`) is the Back button. Worth doing
  rather than letting the TTL run: during a busy drop, eight minutes of a seat
  nobody wants is eight minutes somebody else could have bought it. It returns
  silently if the booking is no longer PENDING — releasing is idempotent by
  design.

---

## Confirming, step by step

`BookingService.confirm` is also not `@Transactional`, for the same reason and
more strongly: it makes two network calls to a payment provider.

```
  idempotency gate  ->  hold still ours?  ->  AUTHORIZE  ->  DB COMMIT
        ->  CAPTURE  ->  release Redis hold  ->  store response
```

**The idempotency gate.** `idempotency.claim(user, key, endpoint,
canonicalise(request))` returns one of four outcomes:

| Outcome | Meaning | What confirm does |
|---|---|---|
| `PROCEED` | our INSERT won; nobody has used this key | do the work |
| `REPLAY` | the key has a stored response | return it verbatim |
| `MISMATCH` | same key, different request hash | `422 IDEMPOTENCY_KEY_REUSED` |
| `IN_FLIGHT` | claimed but unfinished | `409 CONCURRENT_MODIFICATION` |

**1. Is the hold still ours?** `persistence.loadOwned(holdId, caller.id())` —
ownership is in the WHERE clause, so "not yours" and "not found" are the same
404. Status must be `PENDING`; `pending.isExpired(now)` must be false; and then
the real check, `holds.ownsAll(eventId, seatIds, holdId)`. If Redis says the
reservation lapsed we refuse with `HOLD_EXPIRED` rather than quietly completing
— somebody else may already hold these seats, in which case the database write
would fail anyway, but with a far more confusing error *after we had taken the
money*.

**2. Authorize.** `payments.authorize(paymentToken, totalMinor, reference)`.
This reserves funds and moves nothing. A decline throws `PAYMENT_DECLINED`
(402). The provider's raw message is never returned — `PaymentResult.
declineReason` is documented as a safe user-facing string.

**3. Commit the booking.** `persistence.confirmBooking(holdId, userId, now)`, in
its own transaction: re-check PENDING and not expired, load the seats with one
`findForEvent` query, `seat.markBooked()` on each (the `@Version` optimistic
lock is what makes this safe — see `docs/03-concurrency.md`), `booking.confirm
(now)`, which sets `CONFIRMED`, `confirmedAt` and nulls `expires_at`. There is
no explicit `save()`: the entities are managed inside the transaction and
Hibernate's dirty checking writes them at flush. **This is the point of no
return.**

Immediately after, `persistence.loadOwned(...)` runs *again* and the response is
built from that. The entity returned from `confirmBooking` is detached now that
its transaction has committed, so walking its lazy associations would throw
`LazyInitializationException` — `open-in-view` is `false`. `loadOwned`
initialises the seat collection while its own session is open.

**4. Capture.** `payments.capture(authorizationId)`, then `authorizationId =
null` so the catch block knows there is nothing left to void.

**5. Release the Redis hold.** The seats are `BOOKED` in Postgres now, so the
reservation has done its job. Failing to release would be harmless — the TTL
clears it — which is exactly why it is safe to put here.

**6. Store the response.** `idempotency.storeResponse(claim.recordId(), 200,
json, confirmed)`, in its own `REQUIRES_NEW` transaction, so a failure to store
cannot undo the booking. Losing the record is survivable (a retry gets a plain
409 instead of a clean replay); losing the booking would not be.

**The catch block** voids the authorization first — `voidAuthorization` is
contractually non-throwing, so it cannot mask the original failure — then calls
`idempotency.releaseClaim(...)` and rethrows.

---

## The failure-mode analysis

This is the section worth reading slowly. For each crash point: what survives,
and whether it is acceptable.

### During hold creation

| Crash point | Surviving state | Verdict |
|---|---|---|
| Before step 2 | Nothing happened. | Fine. |
| Between 2 and 3 | A Redis hold with no booking behind it. | Self-heals at t+8m when the TTL fires. Cost: one seat briefly unsellable. |
| Step 3 throws | Catch releases the Redis hold immediately. | Self-heals in milliseconds. |
| After 3 commits, before the HTTP response reaches the client | A live PENDING booking and a live Redis hold the client does not know about. | Self-heals at t+8m (Redis) and within 60s after that (reaper). The client's retry generates a *new* UUID, so Redis reports a conflict and they get a 409 for seats they actually hold. `POST /holds` is not idempotent; that is a real rough edge and the mitigation is that it costs at most eight minutes. |

Every one of these degrades to "a seat is briefly unavailable", which is the
correct thing to be bad at.

### During confirmation

| Crash point | Money | Booking | Seat | Verdict |
|---|---|---|---|---|
| After the idempotency INSERT, before anything else | untouched | PENDING | held | The key is stuck `IN_FLIGHT` because `releaseClaim` runs in a `catch` block and a process death runs no catch. Retries with that key get 409 until the nightly cleanup drops it after 1 day. The user can start a fresh checkout with a new key; the booking expires normally. Ugly, not harmful. |
| After the `ownsAll` check, before authorize | untouched | PENDING | held | Nothing done. The hold expires normally. |
| **Between authorize and DB commit** | authorized, not captured | none | held | **No money moves.** Card authorizations expire on their own, typically in 7 days, and the customer sees a pending hold that vanishes. This is the entire reason we authorize rather than charge. |
| **Between DB commit and capture** | authorized, never captured | CONFIRMED | BOOKED | **We have given away a seat for free.** This is the one genuinely bad outcome. |
| Between capture and Redis release | captured | CONFIRMED | BOOKED | A Redis hold outlives its booking. It expires by itself, and the seat is BOOKED in Postgres anyway, so nobody else could have taken it. Harmless. |
| Between release and `storeResponse` | captured | CONFIRMED | BOOKED | The booking is correct and paid. The retry sees a claim with no stored response and gets a 409; a retry with a fresh key gets `BOOKING_NOT_PENDING`. The user sees an error for a booking they actually have, and it is visible under `GET /bookings`. Confusing, not incorrect. |

### The one bad window, stated plainly

**Crash between "DB commit" and "capture" means a confirmed booking that was
never paid for.** There is no way to eliminate it without a distributed
transaction across Postgres and a third-party payment API, which does not exist.

What the design does about it is make it as small and as survivable as possible:

1. **It is placed last.** Everything that can fail for an ordinary reason — a
   lapsed hold, a declined card, a lost seat — happens *before* the commit,
   where the compensating action is a void that costs the customer nothing. The
   only thing left after the commit is one call to an already-approved
   authorization.
2. **The window is one network call wide.** Between the commit and the capture
   there is a `loadOwned` and a mapper call, both local. (Strictly, if the
   mapper threw, the catch block would void an authorization for a booking that
   is already committed and land us in the same window — narrow, and it is worth
   knowing it is there.)
3. **The failure is in our favour to detect, not the customer's.** The customer
   has a valid ticket. We are the party out of pocket, and we can find it later.

**What a real system does instead of hoping.** Write an **outbox** row in the
same transaction as the booking — `(booking_id, authorization_id, amount,
status = PENDING_CAPTURE)` — and have a worker drain it. Because the outbox row
and the booking commit or roll back together, a booking can never exist without
its capture instruction. On top of that, a **nightly settlement job** compares
our CONFIRMED bookings against the provider's captured-transaction report and
raises anything that does not match. Neither is in this repository, and
`docs/02-security.md` lists both under what a production deployment would add.

The reason to write this down rather than leave it out: *"which failure did you
choose to accept?"* is the question that separates someone who has thought about
this from someone who has not. Choosing to accept "occasionally we owe ourselves
a reconciliation" over "occasionally we sell one seat twice" is the whole
argument.

---

## Idempotency

The problem is concrete: a user taps Pay on a train, the request reaches us, we
create the booking, and the response is lost in a tunnel. The phone retries.

### The check-then-act race

The naive implementation is:

```java
if (repo.findByKey(k).isEmpty()) {     // (1)
    doTheWork();
    repo.save(new Key(k, response));   // (2)
}
```

Two simultaneous retries both run (1), both see nothing, and both do the work.
No amount of care in Java closes that gap — the two requests may be on different
servers entirely, so there is no lock, no `synchronized`, and no local flag that
both of them can see.

### Write first, and let the unique constraint decide

`IdempotencyService.claim` inverts it. Both requests try to INSERT. Postgres
enforces `UNIQUE (user_id, idem_key)` at the row level, so exactly one can
succeed. The loser gets a `DataIntegrityViolationException`, which is not a
failure but an **answer**: somebody else is already handling this.

This is the same shape as the seat guarantee itself. When two actors must not
both proceed, the cheapest correct referee is usually a unique index.

`saveAndFlush`, not `save`. Without the flush Hibernate would defer the INSERT
to the end of the transaction and we would leave the method believing we had won
a race that has not been run yet.

On the violation path, if the row still cannot be found, the winner's
transaction has not committed and the row is invisible under `READ COMMITTED` —
so the outcome is `IN_FLIGHT`, not an error.

### Why `REQUIRES_NEW` on every method

The idempotency record must **outlive the work it protects**. If the key were
written in the same transaction as the booking, a booking that rolled back would
roll its key back too, and the retry would find nothing and do the work again.
That defeats the entire mechanism. `Propagation.REQUIRES_NEW` suspends any
surrounding transaction and commits the key independently.

### Why the request hash

`request_hash CHAR(64)` is a SHA-256 of the canonical request body. A key is a
promise that the *same* request is being retried. A different body under the
same key is either a client bug or an attempt to fish for another request's
stored response, and either way the honest answer is `422`, not a replay of
something else.

`canonicalise` uses Jackson on the `ConfirmBookingRequest` **record**. Records
serialise their components in declaration order, so the same request always
produces the same JSON and therefore the same hash. If this were a `Map`,
iteration order could vary and two identical retries could hash differently —
which would look like a mismatch and reject a perfectly valid retry.

### Why keys are scoped per user

Keys are client-chosen strings. If the constraint were `UNIQUE (idem_key)`
alone, a malicious client could claim the key `"1"` and either block another
user's request or read back their stored response. `UNIQUE (user_id, idem_key)`
makes one user's key namespace unreachable from another account.

### Why a failed attempt releases its claim

`releaseClaim` deletes the row when the work failed for a reason the client can
reasonably retry — a declined card being the obvious one. Keeping the key would
mean the retry replays "declined" forever, even after the customer fixes their
card.

The comment on that method says what it deliberately does *not* delete: keys
whose work genuinely completed, and keys whose work may have partially happened.
**In the current code the `catch` in `confirm` calls `releaseClaim`
unconditionally, including for a failure thrown after the booking committed.**
The practical consequence is bounded (the retry then hits
`BOOKING_NOT_PENDING`, and no second booking or second charge is possible),
but it is narrower than the intent the javadoc states. The right fix is to
release the claim only for failures raised before `confirmBooking` returns.
Flagged here rather than hidden, on the same principle as the documented N+1 in
`docs/01-data-model.md`.

---

## Why authorize/capture, not charge-then-refund

Real card payments are two steps. **Authorize** reserves money on the customer's
card and returns a reference. **Capture** moves it. Between the two you can
**void**, and nothing is taken.

That split exists precisely for a situation like this one: we must not take
money for a booking that then fails to save. So the sequence is authorize →
commit → capture, and if the commit fails we void and the customer sees nothing
but a hold on their card that disappears.

Charge-then-refund gets the same end state and is worse in every intermediate
one:

- The customer's money genuinely leaves their account and comes back days later.
  A hold that vanishes is invisible; a debit-and-refund produces a support
  ticket and a lost customer.
- A refund is a second operation that can itself fail, so the compensating
  action needs its own retry logic and its own failure mode. A void of an
  uncaptured authorization is far cheaper and, at most providers, cannot
  partially succeed.
- Refunds often carry a fee and always carry a settlement delay; voids do not.

The interface `booking/PaymentGateway.java` encodes this in the type system —
`authorize`, `capture`, `voidAuthorization` — so no implementation can offer a
single `charge` shortcut. `voidAuthorization` is documented as **must not
throw**, because it runs from failure-handling paths and an exception there
would replace a recoverable failure with an unrecoverable one *and* leave the
customer's money reserved.

`StubPaymentGateway` is deterministic, not random: `tok_demo_decline` declines,
everything else approves, and a zero or negative amount declines as "Invalid
amount" because that means a pricing bug upstream. A stub that fails 5% of the
time produces tests that fail 5% of the time, and people learn to re-run a flaky
suite instead of reading it. Note that the stub is a plain `@Component` with no
profile condition, so it is active everywhere including production — deliberate,
named so nobody can mistake it, and called out in `docs/07-deployment.md`.

---

## The reaper

`booking/HoldExpiryReaper.java`. Two scheduled jobs.

### What it does not do

**It does not release seats.** Redis releases seats, on its own, the instant a
TTL elapses, whether or not this application is running. That is the entire
reason holds live in Redis rather than in a database status column.

What the reaper does is tidy the *database*: turn PENDING bookings whose hold
has evaporated into EXPIRED ones, so "my bookings" does not show a phantom
reservation and the `booking_seats` rows stop claiming the seat through the
partial unique index.

The distinction is the answer to "what happens if this job dies?" — seats still
get released on time, users can still book, and the only symptom is stale
PENDING rows. Compare a design where a hold is a database status: there, this
job dying means seats stay locked **forever**. Choosing which component can fail
harmlessly is most of what designing for reliability means.

### `fixedDelay`, not `fixedRate`

```java
@Scheduled(fixedDelayString = "${seatlock.reaper.fixed-delay:60s}")
```

`fixedRate` schedules the next run a fixed interval after the previous one
*started*. If a sweep ever takes longer than the interval, runs pile up on top
of each other and the situation gets worse exactly when it is already bad.
`fixedDelay` measures from when the previous run *finished*, so a slow sweep
simply pushes the next one back.

The batch is capped at `BATCH_SIZE = 200`. If the service had been down for an
hour there could be tens of thousands of expired holds; loading them all would
spike memory and hold one very long transaction open — which in Postgres also
blocks autovacuum from cleaning up rows newer than that transaction's snapshot.
Small batches run often keep every transaction short.

Each booking is expired through `persistence.expireBooking(id, now)`, which is
`Propagation.REQUIRES_NEW`, so one failure — typically an optimistic-lock
conflict with a user confirming at that exact instant — does not roll back the
other 199. Losing that race is the *correct* outcome: the user paid, so their
booking must win over our cleanup. `expireBooking` re-checks status and expiry
inside its own transaction, because the reaper's query ran a moment ago.

A Micrometer counter, `seatlock.holds.expired`, is incremented per sweep. That
is the number that answers "is the 8-minute TTL right?", and it cannot be
answered retroactively by grepping logs.

The second job, `cleanUpStaleRows`, runs on cron (`0 15 3 * * *` by default —
a quiet hour, not a random offset from whenever the process last restarted) and
deletes refresh tokens expired more than 30 days ago and idempotency keys older
than 1 day.

### The multi-instance caveat

Every instance runs both jobs. With three instances the work is done three
times. Here that is harmless — `expireBooking` re-checks status inside its own
transaction, so the second and third attempts find nothing to do, and the
optimistic lock settles any genuine tie.

It is **wasteful rather than wrong**. The standard fix is **ShedLock**, which
uses a database row as a mutex so exactly one instance runs each scheduled task.
It is not added here because the waste is a few queries a minute against
`bookings_pending_expiry_idx`, an index built for exactly this query. Knowing
the name of the fix and why you did not need it yet is the point — and the
answer changes the moment a scheduled job stops being idempotent.

### `expireStalePendingClaiming` — the on-demand sweep

A timer alone cannot close one real window, and this is the subtle part of the
whole lifecycle.

A PENDING booking writes `booking_seats` rows with `active = true`, so the
partial unique index treats the seat as claimed **before payment**. Redis and
Postgres then expire on different schedules:

```
  t=8m00s  Redis TTL fires. The seat is free as far as Redis is concerned.
  t=8m01s  Bob's hold succeeds in Redis...
           ...but his INSERT into booking_seats hits the unique index,
           because Alice's abandoned PENDING row is still active.
  t=8m45s  The background reaper finally runs and expires Alice's booking.
```

For those 45 seconds the seat looks taken to everyone while belonging to nobody.
Sweeping on a timer can only make that window shorter, never zero, and
shortening it means running the sweep more often for no benefit the rest of the
time.

So `BookingPersistence.createPendingBooking` calls
`bookings.expireStalePendingClaiming(seatIds, now)` **first, on every hold**: it
expires, right now, any already-lapsed PENDING booking still claiming one of
these specific seats. The background reaper stays, because seats nobody is
asking for still need tidying, but the user-visible window closes to zero.

The bulk UPDATE carries `@Modifying(clearAutomatically = true,
flushAutomatically = true)` and sets `b.version = b.version + 1` by hand. A bulk
JPQL update goes straight to the database and bypasses both the persistence
context and Hibernate's optimistic locking. Without `clearAutomatically`, an
entity already loaded in this transaction would report the old status; without
the manual version bump, an in-flight confirmation could commit over the top of
the expiry.

---

## Spring transaction notes

Three things about transaction boundaries here are worth being able to defend.

### `@Transactional` silently does nothing on self-invocation

Spring implements `@Transactional` with a **proxy**: something wraps the bean
and opens a transaction as the call passes through the wrapper. A call from one
method of a class to another method of the *same* class never leaves the object,
so it never passes through the proxy, so **the annotation has no effect at all
— and nothing warns you**. The code looks transactional, the logs show no
transaction, and the bug only surfaces when a partial failure fails to roll
back.

### Which is why `BookingPersistence` is a separate bean

Splitting the transactional units of work into their own `@Service` means every
call from `BookingService` is a real call through a real proxy. That is the
first reason. The second is that it forces the boundary to be a **decision**:
`BookingService` orchestrates — Redis, the payment gateway, the mapper — and
calls into `BookingPersistence` only for the parts that must be atomic in
Postgres. Reading the two files side by side, it is obvious which calls are
inside a transaction and which are not.

### Network calls stay outside transactions

A database transaction held open across a call to a third party parks one
connection from a pool of twenty for as long as that third party feels like
taking. A payment provider having a slow afternoon would then exhaust the pool
and take down browsing, login and everything else — one slow dependency becomes
a total outage. So `holds.acquire`, `holds.release`, `payments.authorize` and
`payments.capture` are all called from `BookingService`, never from inside a
`BookingPersistence` method.

`loadOwned` is `@Transactional(readOnly = true)` and explicitly touches the seat
collection before returning. That is not decoration: `open-in-view` is `false`,
so the mapper runs outside the session and anything it needs must be initialised
inside it. `readOnly` also lets Hibernate skip dirty-check snapshots and tells
Postgres it can take a cheaper snapshot.

---

## One known bug

`BookingPersistence.cancelBooking` deliberately allows a `CONFIRMED` booking
through its guard (`isTerminal() && status != CONFIRMED`) and releases its
seats — but then calls `booking.cancel(now)`, and `Booking.cancel` throws
`IllegalStateException` when `status.isTerminal()`, which **includes
`CONFIRMED`**. So `POST /bookings/{id}/cancel` on a confirmed booking currently
produces a 500 `INTERNAL_ERROR` rather than the `200` with `status: "CANCELLED"`
that `docs/API.md` documents. Cancelling a PENDING booking works correctly, and
that is the only path the test suite covers
(`DefenceInDepthIT.cancellationReleasesTheSeatFromTheUniqueIndex`).

The fix is one line — either narrow `BookingStatus.isTerminal()` to mean "no
longer changeable" (excluding `CONFIRMED`), or have `Booking.cancel` permit
`PENDING` and `CONFIRMED` explicitly. The second is safer, because
`isTerminal()` is used elsewhere. It is recorded here rather than quietly fixed
in prose, because a document that describes behaviour the code does not have is
worse than no document.

---

## The numbers, in one place

| Setting | Value | Where |
|---|---|---|
| Hold TTL | 8 minutes (480 s) | `seatlock.hold.ttl`, `HOLD_TTL` |
| Extension | 3 minutes, once | `seatlock.hold.extension` |
| Max seats per hold | 10 | `HoldProperties`, `CreateHoldRequest` |
| Reaper interval | 60 s (`fixedDelay`) | `seatlock.reaper.fixed-delay` |
| Reaper batch | 200 bookings | `HoldExpiryReaper.BATCH_SIZE` |
| Nightly cleanup | 03:15 | `seatlock.reaper.cleanup-cron` |
| Idempotency retention | 1 day | `HoldExpiryReaper.IDEMPOTENCY_RETENTION` |
| Refresh-token retention | 30 days after expiry | `cleanUpStaleRows` |
| DB pool | 20, 3 s connection timeout | `application.yml` |
| Redis timeout | 2000 ms | `application.yml` |

Next: `docs/05-testing.md`, which is how any of the above is actually proven.
