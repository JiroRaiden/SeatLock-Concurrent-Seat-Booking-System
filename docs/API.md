# SeatLock API contract

Base URL: `/api/v1`. All bodies are JSON. All timestamps are ISO-8601 with an
offset (`2026-09-04T19:30:00Z`). All money is an **integer in paise** — the field
name always ends in `Minor` so you can never mistake it for rupees.

---

## The booking flow, end to end

This is the sequence the whole system exists to serve. Read this before the
endpoint list; the endpoints make more sense once the shape is clear.

```
 1. GET  /events                      browse
 2. GET  /events/{id}/seatmap         render the auditorium
 3. ......... user clicks seats ......  ← purely client-side, NO network calls
 4. POST /events/{id}/holds           "Proceed" → reserve seats, start the clock
 5. POST /bookings/{id}/confirm       "Pay" → permanent booking
```

**Step 3 is deliberately offline.** Clicking a seat does not call the server.
Selection is local UI state; the reservation only happens when the user commits
by pressing *Proceed*. This is how BookMyShow behaves, and it is not a UX
detail — it is the reason the system scales. If every seat tap took a lock,
a popular drop would generate thousands of reservations per second that are
almost all going to be abandoned three seconds later.

---

## Errors

Every non-2xx response has exactly this shape. No endpoint invents its own.

```json
{
  "code": "SEAT_UNAVAILABLE",
  "message": "2 of the 4 seats you selected were taken while you were choosing.",
  "status": 409,
  "timestamp": "2026-09-02T11:42:07.318Z",
  "traceId": "b7f2c1a9e4d80351",
  "fieldErrors": { "email": "must be a well-formed email address" },
  "details": { "unavailableSeatIds": [4471, 4472] }
}
```

`fieldErrors` and `details` are omitted when empty. `message` is always safe to
show a user: it never contains a stack trace, a SQL fragment, or an internal id
the user does not already possess.

### Error codes

| code | status | when |
|---|---|---|
| `VALIDATION_FAILED` | 400 | request body failed bean validation; see `fieldErrors` |
| `MALFORMED_REQUEST` | 400 | unparseable JSON, or an unknown field was sent |
| `UNAUTHENTICATED` | 401 | missing, malformed, or expired access token |
| `INVALID_CREDENTIALS` | 401 | login: wrong email or password (never says which) |
| `TOKEN_REUSE_DETECTED` | 401 | a rotated refresh token was replayed; whole chain revoked |
| `FORBIDDEN` | 403 | authenticated, but not allowed to touch this resource |
| `NOT_FOUND` | 404 | resource does not exist, **or** exists but is not yours |
| `SALES_CLOSED` | 409 | the event is no longer selling |
| `SEAT_UNAVAILABLE` | 409 | one or more seats are booked, blocked, or held by someone else |
| `HOLD_EXPIRED` | 409 | the reservation clock ran out before confirmation |
| `BOOKING_NOT_PENDING` | 409 | confirm/cancel called on a booking in the wrong state |
| `CONCURRENT_MODIFICATION` | 409 | optimistic lock lost; the client may safely retry |
| `IDEMPOTENCY_KEY_REUSED` | 422 | same key, different request body |
| `PAYMENT_DECLINED` | 402 | the payment provider refused the charge; the hold survives so the user can retry |
| `TOO_MANY_REQUESTS` | 429 | rate limit; `Retry-After` header carries the wait in seconds |
| `INTERNAL_ERROR` | 500 | anything unexpected; details are logged, never returned |

**404-instead-of-403 for other people's bookings is deliberate.** Returning 403
would confirm that a booking with that id exists, which lets an attacker
enumerate the resource space even though they cannot read it. Denying existence
leaks nothing.

---

## Authentication

Send `Authorization: Bearer <accessToken>` on protected endpoints.

### `POST /auth/register` — public

```json
{ "email": "a@b.com", "password": "Str0ng-Pass!", "displayName": "Bishal" }
```
→ `201` `AuthResponse`

Password policy: 10–128 characters. Length is the requirement, not a
character-class puzzle — see `docs/02-security.md` for why.

### `POST /auth/login` — public, rate limited
```json
{ "email": "a@b.com", "password": "Str0ng-Pass!" }
```
→ `200` `AuthResponse`, or `401 INVALID_CREDENTIALS`

### `POST /auth/refresh` — public
```json
{ "refreshToken": "<opaque>" }
```
→ `200` `AuthResponse` with a **new** refresh token. The old one is dead.

### `POST /auth/logout` — authenticated
```json
{ "refreshToken": "<opaque>" }
```
→ `204`

### `GET /auth/me` — authenticated
→ `200` `UserSummary`

**`AuthResponse`**
```json
{
  "accessToken": "eyJhbGciOi...",
  "refreshToken": "9f2c...",
  "tokenType": "Bearer",
  "expiresInSeconds": 900,
  "user": { "id": 1, "email": "a@b.com", "displayName": "Bishal", "role": "ROLE_USER" }
}
```

---

## Events — all public

### `GET /events`
Query: `category`, `city`, `q`, `page` (0-based), `size` (≤50).

→ `200`
```json
{
  "content": [ EventSummary ],
  "page": 0, "size": 20, "totalElements": 6, "totalPages": 1, "last": true
}
```

**`EventSummary`**
```json
{
  "id": 1,
  "title": "Meridian",
  "subtitle": "The last signal from Europa",
  "category": "MOVIE",
  "language": "English",
  "certification": "UA13+",
  "durationMinutes": 148,
  "posterUrl": null,
  "startsAt": "2026-09-02T17:38:00Z",
  "salesCloseAt": "2026-09-02T17:38:00Z",
  "venue": { "id": 1, "name": "Aurora IMAX, Park Street", "city": "Kolkata" },
  "minPriceMinor": 28000,
  "availableSeats": 141,
  "totalSeats": 202
}
```

`posterUrl` is `null` in the seeded data. The frontend generates poster artwork
deterministically from the title — see the "Design decisions" section of
`frontend/README.md`.

### `GET /events/{id}`
→ `200` `EventDetail` = `EventSummary` + `description`, `backdropUrl`, `tiers[]`.

### `GET /events/{id}/seatmap`

The one call that renders the auditorium. Returns every seat, already grouped
into rows and sorted, so the client does zero layout maths.

→ `200`
```json
{
  "eventId": 1,
  "venueName": "Aurora IMAX, Park Street",
  "salesCloseAt": "2026-09-02T17:38:00Z",
  "tiers": [
    { "id": 1, "name": "RECLINER", "priceMinor": 65000, "colour": "#F5C451" },
    { "id": 2, "name": "PRIME",    "priceMinor": 45000, "colour": "#5EC8A0" },
    { "id": 3, "name": "CLASSIC",  "priceMinor": 28000, "colour": "#7DA2F0" }
  ],
  "rows": [
    {
      "rowLabel": "A",
      "rowIndex": 1,
      "section": "RECLINER",
      "seats": [
        { "id": 4401, "label": "A1", "seatNumber": 1, "colIndex": 1,
          "tierId": 1, "priceMinor": 65000, "status": "AVAILABLE" }
      ]
    }
  ],
  "summary": { "available": 141, "booked": 59, "blocked": 2, "held": 4 }
}
```

Seat `status` is one of `AVAILABLE`, `BOOKED`, `BLOCKED`, `HELD`.

**`HELD` exists only in this response.** It is not a database state — it is
computed at read time by asking Redis which of this event's seats currently have
a live hold. The distinction matters: `BOOKED` is durable and permanent,
`HELD` is a few minutes old and may evaporate on its own. The UI renders held
seats as unavailable-but-different, so a user watching a busy screen can see
seats flicker back into availability.

`rows` are ordered front-to-back by `rowIndex`; seats within a row by
`colIndex`. `colIndex` already contains the centre-aisle gap, so rendering is
`gridColumn: colIndex` with no special-casing.

---

## Holds — authenticated

### `POST /events/{id}/holds` — rate limited

Reserve seats and start the clock.

```json
{ "seatIds": [4401, 4402, 4403] }
```

Validation: 1–10 ids, all belonging to this event. Duplicates are de-duplicated
rather than rejected — a double-tap on the same seat is a UI accident, not an
error worth a 400.

→ `201`
```json
{
  "holdId": "0d1f5a2c-8e4b-4a19-9f33-1c2b7e5d6a80",
  "eventId": 1,
  "expiresAt": "2026-09-02T11:50:07Z",
  "ttlSeconds": 480,
  "totalMinor": 195000,
  "seats": [ { "id": 4401, "label": "A1", "priceMinor": 65000 } ]
}
```

`holdId` is **also the booking's `publicId`**. One identifier, deliberately:
the reservation and the pending booking are the same thing viewed from Redis and
from Postgres respectively. It doubles as the ownership token for the Redis
hold, which is what makes release safe — see `docs/03-concurrency.md`.

→ `409 SEAT_UNAVAILABLE` with `details.unavailableSeatIds` listing exactly which
seats were lost. Holds are **all-or-nothing**: if one seat of four is gone, the
other three are not reserved, so a failed attempt never strands seats.

### `POST /holds/{holdId}/extend`
Grants one extension of `HOLD_EXTENSION` (3 min), once per hold, only while the
hold is still alive. → `200 { "expiresAt", "ttlSeconds" }`

### `DELETE /holds/{holdId}`
Release early (user pressed Back). → `204`

---

## Bookings — authenticated

### `POST /bookings/{holdId}/confirm`

Header: `Idempotency-Key: <uuid>` — **required**.

```json
{ "paymentMethod": "CARD", "paymentToken": "tok_demo_success" }
```

`paymentToken` is a token from a payment provider, never a card number. This
project stubs the provider; the stub accepts `tok_demo_success` and rejects
`tok_demo_decline`, which is enough to exercise both paths in tests. Cards never
touch this server, which is the entire point of the tokenisation pattern.

→ `200` `BookingDetail`, or `409 HOLD_EXPIRED` / `409 SEAT_UNAVAILABLE`.

Retrying with the same `Idempotency-Key` replays the original response byte for
byte and creates nothing new.

### `GET /bookings` — the caller's own bookings, newest first
→ `200` paginated `BookingSummary`

### `GET /bookings/{holdId}`
→ `200` `BookingDetail`, or `404` if it is not yours.

### `POST /bookings/{holdId}/cancel`
→ `200` `BookingDetail` with `status: "CANCELLED"`; seats return to sale.

**`BookingDetail`**
```json
{
  "id": "0d1f5a2c-8e4b-4a19-9f33-1c2b7e5d6a80",
  "reference": "SL-7QK4M2",
  "status": "CONFIRMED",
  "totalMinor": 195000,
  "createdAt": "2026-09-02T11:42:07Z",
  "expiresAt": null,
  "confirmedAt": "2026-09-02T11:43:11Z",
  "event": {
    "id": 1, "title": "Meridian", "startsAt": "2026-09-02T17:38:00Z",
    "venue": { "id": 1, "name": "Aurora IMAX, Park Street", "city": "Kolkata" }
  },
  "seats": [ { "id": 4401, "label": "A1", "section": "RECLINER", "priceMinor": 65000 } ]
}
```

---

## Rate limits

| endpoints | budget |
|---|---|
| `/auth/login`, `/auth/register`, `/auth/refresh` | 10 / minute / IP |
| `POST /events/{id}/holds` | 30 / minute / IP |
| everything else | unlimited (protected by upstream infrastructure) |

Exceeding a budget returns `429` with `Retry-After` in seconds.
