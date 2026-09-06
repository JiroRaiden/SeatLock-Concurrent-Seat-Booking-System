# Security

This is written as a threat model rather than a feature list. For each area:
**the attack**, **the defence in this codebase** (with the file that implements
it), and **the residual risk** — what is still true after the defence. The last
section is an honest list of what a real production deployment would add that
this one does not have.

Two rules run through everything below and are worth stating up front:

1. **The UI is never a control.** The frontend hides the Proceed button when
   sales close; the server checks `Event.isOpenForSale(now)` anyway on every
   hold. Anyone can POST directly.
2. **Anticipated failures are described precisely; unanticipated ones are not
   described at all.** That is the rule `GlobalExceptionHandler` enforces, and
   it is the reason a stack trace never reaches a browser.

---

## 1. Password storage

**Attack.** The `users` table leaks — a backup on an open S3 bucket, a read-only
SQL injection, a stolen laptop. The attacker now has every user's password
offline and can grind at it with a GPU rig at no cost to us. Because people
reuse passwords, cracking one gets them into that person's bank.

**Defence.** `SecurityConfig.passwordEncoder()` returns
`new BCryptPasswordEncoder(12)`. BCrypt salts each hash automatically and stores
the salt inside the 60-character output string, which is why there is no
separate salt column in the schema.

**Why 12 specifically.** The cost factor is a power of two: 12 means 2¹² = 4 096
key-derivation rounds, roughly 250 ms on a modern server. That number is chosen
from both ends. Too low and an attacker with a leaked table tries billions of
candidates per second on a GPU. Too high and your own login endpoint becomes a
denial-of-service amplifier, because each attempt costs *you* the same CPU it
costs *them* — at 1 second per hash, 20 concurrent login attempts saturate a
small instance. 10–12 is the current sensible band, and it should be
re-examined every couple of years as hardware improves. This interacts directly
with rate limiting (section 12): the reason `RateLimitFilter` runs *before*
authentication in the filter chain is that a limiter placed after it would
already have paid the 250 ms it exists to avoid.

**Why not Argon2id.** Argon2id is the stronger modern choice because it is
*memory-hard* — it forces an attacker to buy RAM per parallel guess, which is
what defeats the massive parallelism of a GPU. BCrypt's memory footprint is
about 4 KB and fits in cache many times over. Argon2id is not used here for two
practical reasons: Spring's implementation pulls in BouncyCastle, and it has
three interacting parameters (memory, iterations, parallelism) that have to be
tuned against the actual deployment hardware to mean anything — a default-tuned
Argon2id can be *weaker* than a well-chosen BCrypt. The migration path is left
open: `users.password_hash` is `VARCHAR(100)` rather than the 60 BCrypt needs
precisely so the swap needs no DDL change, and Spring's
`DelegatingPasswordEncoder` can upgrade hashes on next login.

**The 72-byte trap.** BCrypt only reads the first 72 bytes of a password.
Anything beyond that is silently ignored, so `"<71 chars>A"` and
`"<71 chars>B"` are the same password. Two consequences worth knowing: a length
cap above 72 gives no extra strength, and pre-hashing a long password with an
unencoded digest before BCrypt (a common "fix") introduces a null-byte
truncation bug in some implementations. `RegisterRequest` caps password length
at 128 anyway, but for a different reason — to stop someone posting a megabyte
of text and making the server spend real CPU on it. The minimum is 10.

**Why length and not composition rules.** `RegisterRequest` requires 10–128
characters and nothing else: no "one uppercase, one digit, one symbol". Those
rules push people towards `Password1!` — short, predictable, in every cracking
dictionary — while a 14-character passphrase they can actually remember is far
stronger. NIST SP 800-63B moved away from composition rules for exactly this
reason.

**Residual risk.** A 10-character password from a common wordlist is still
crackable at cost 12 given a leaked table and enough time. There is no breached
password check (a Have-I-Been-Pwned k-anonymity lookup at registration would
close most of this), no MFA, and no password strength meter.

---

## 2. The access / refresh token split

**Attack.** A stolen credential grants access. How long, and can we stop it?

**Defence.** `JwtService` mints two very different things:

| | Access | Refresh |
|---|---|---|
| Form | signed JWT (HS256) | 256 random bits, base64url |
| Lifetime | 15 minutes | 7 days |
| Checked against DB | never | every use |
| Revocable | **no** | yes |

The access token is stateless so that authenticating a request costs one HMAC
verification and **zero I/O** — that is what lets the API scale horizontally with
no shared session store and no sticky sessions
(`SessionCreationPolicy.STATELESS` in `SecurityConfig`). The price is that it
cannot be revoked, so **its lifetime is its blast radius**, hence 15 minutes.

The refresh token is deliberately **not** a JWT. It carries no claims, so there
is nothing in it to trust or to get wrong; it is a lookup key into a table we
control, which is what makes revocation, rotation and reuse detection possible
at all.

The token's subject is the numeric user id, not the email: emails change,
primary keys do not, and a token that outlives an email change should still
identify the right account. The role is embedded so authorisation needs no
database lookup.

**Residual risk, stated plainly.** Logout revokes the refresh token
immediately; the access token stays valid for up to its remaining 15 minutes,
because nothing consults a database when it is verified. Same for an admin
demoted right now — they keep admin for up to 15 minutes. `AuthService.logout`
documents this rather than pretending otherwise. The fixes, if instant
revocation ever matters, are a shorter TTL or a denylist keyed on the token's
`jti` claim (which `JwtService.issueAccessToken` already sets, precisely as the
hook a future denylist would hang off).

---

## 3. Refresh tokens are hashed with SHA-256, not BCrypt

**Attack.** The `refresh_tokens` table leaks. If tokens were stored in
plaintext, the attacker holds live 7-day sessions for every user.

**Defence.** `JwtService.hashRefreshToken` stores a hex-encoded SHA-256; the raw
token is never written anywhere. `refresh_tokens.token_hash` is `CHAR(64)`. A
leaked table is useless on its own: you cannot present a hash to the API.

**Why a fast hash is correct here and would be wrong for a password.** This is
the single most commonly misunderstood point in this area, and the answer turns
entirely on **entropy**.

- A password is low-entropy and human-chosen. There is a dictionary to try. So
  the only defence is to make each guess *expensive* — hence a deliberately slow
  hash. BCrypt at cost 12 turns a billion guesses per second into four thousand.
- A refresh token is 32 bytes from a `SecureRandom` — 2²⁵⁶ possibilities. There
  is no dictionary. Brute force is not slow, it is *impossible*: no amount of
  hardware searches a 256-bit space. The only property we need from the hash is
  **preimage resistance**, which SHA-256 has.

Making it slow would therefore buy nothing and cost something: every
`/auth/refresh` would pay 250 ms of BCrypt, and BCrypt's 72-byte limit and
per-hash random salt would also mean you could not look the token up by hash at
all — you would have to scan the table and `matches()` against every row.
`RefreshTokenRepository.findByTokenHash` is an indexed equality lookup precisely
because the hash is deterministic.

**Residual risk.** SHA-256 is unsalted, so identical tokens produce identical
hashes — irrelevant here, since tokens are unique random values. A token stolen
*in transit* or from the client's storage is fully usable until it is rotated or
revoked; see section 18.

---

## 4. Refresh token rotation with reuse detection

**Attack.** An attacker steals a refresh token (XSS, a shared machine, a
malicious extension). Without rotation they hold a rolling session for seven
days and nothing ever notices.

**Defence.** `AuthService.refresh`. Every use of a refresh token consumes it and
issues a replacement, with `refresh_tokens.replaced_by` pointing at the
successor so the chain is auditable. A token should therefore be presented
**exactly once, ever**.

If an already-revoked token turns up again, two parties hold it — the real user
and the thief — and we cannot tell which one is in front of us. So we assume the
worst: `refreshTokens.revokeAllForUser(...)` burns the entire family and the
caller gets `401 TOKEN_REUSE_DETECTED`. The legitimate user is forced to log in
again, which is mildly annoying and vastly better than an attacker holding a
rolling session for a week. This is the behaviour the OAuth 2.0 Security Best
Current Practice recommends for clients that cannot keep a secret — which
includes every single-page application.

The client side matters as much as the server side here.
`frontend/src/lib/apiClient.ts` holds the in-flight refresh promise in a
module-level variable, so ten parallel 401s await **one** refresh instead of
firing ten. Without that mutex, the naive "refresh on every 401" implementation
would present the same rotated token twice and log the user out precisely when
the app is busiest — a self-inflicted `TOKEN_REUSE_DETECTED`.

**Residual risk.** Detection is retroactive: it fires the first time the *loser*
of the race uses the token, which may be the legitimate user, and by then the
attacker has already had one valid session window. There is no device
fingerprinting, no IP-change heuristic, and no notification to the user that
their session was killed for a security reason.

---

## 5. The classic JWT pitfalls

**Attack A — `alg: none`.** Early JWT libraries would happily "verify" a token
whose header claimed no algorithm, so an attacker could strip the signature,
edit `"role": "ROLE_ADMIN"` into the payload, and be believed.

**Attack B — algorithm confusion.** A library that picks the verification
algorithm from the *token's own header* can be handed an `HS256` token signed
with the server's **public** RSA key as the HMAC secret, and will verify it.

**Attack C — weak key.** HS256 with a short secret is brute-forceable offline.
Once the secret is known, forging an admin token costs nothing.

**Defence.** All three are handled in `JwtService`:

```java
Jwts.parser()
    .verifyWith(signingKey)      // binds verification to HMAC with THIS key
    .requireIssuer(issuer)       // reject anything not minted by us
    .clockSkewSeconds(30)
    .build()
    .parseSignedClaims(token);
```

`verifyWith(SecretKey)` (JJWT 0.12) binds verification to a MAC algorithm at the
API level, so a token whose header says `{"alg":"none"}` or `{"alg":"RS256"}`
cannot pass — it is rejected before the claims are even read. This is why the
parser is built this way and never with the deprecated `setSigningKey` plus
manual algorithm inspection. For attack C, the constructor decodes the Base64
secret and **refuses to start the application** if it is under 32 bytes:

```java
if (keyBytes.length < MIN_KEY_BYTES) {
    throw new IllegalStateException("JWT secret decodes to " + keyBytes.length
        + " bytes; HS256 requires at least " + MIN_KEY_BYTES + " ...");
}
```

Failing at startup is the point. A service that boots with a weak signing key
behaves completely normally right up until someone mints their own admin
session, and nothing about its behaviour would reveal the problem.

`requireIssuer` matters when a signing key is ever shared between services:
without it, a token minted by a sibling service for its own purposes would be
accepted here as a valid login. `clockSkewSeconds(30)` is kept tight because it
is added to every token's effective lifetime.

`parseAccessToken` returns `Optional.empty()` for **every** failure mode —
expired, bad signature, malformed, wrong issuer, unparseable subject, unknown
role — and logs at DEBUG. Telling a caller *why* their token failed is telling
an attacker which part of their forgery to fix next.

**Residual risk.** The signing key is symmetric and shared by every instance;
rotating it invalidates every live access token at once (bounded at 15 minutes,
so survivable, but there is no key-id header to support overlapping keys).

---

## 6. Account enumeration

**Attack.** Feed a list of leaked email addresses at the API and learn which
ones have accounts here. That is the first step of a credential-stuffing
campaign, and it is also a privacy leak in its own right (which addresses
bought tickets to what).

**Defence at login — two separate channels, both closed.**

*The message.* Whether the email is unknown, the password is wrong, or the
account is disabled, `AuthService.login` throws exactly
`ErrorCode.INVALID_CREDENTIALS` → `401 "Email or password is incorrect."`
`GlobalExceptionHandler.handleAuthentication` also refuses to surface Spring's
own `AuthenticationException` messages, which distinguish "User not found" from
"Bad credentials". `LoginRequest` deliberately has **no** `@Email` annotation,
unlike `RegisterRequest`: validating the format on login would answer "is this
even an email?" before the credential check runs, producing a different response
for a malformed address than for a wrong password.

*The clock.* This is the part that is usually missed. The obvious implementation
returns early when the user is not found, so a miss answers in ~1 ms and a hit
takes ~250 ms while BCrypt runs. That difference is trivially measurable over
the network and re-creates the oracle the identical message just closed — the
response text is the same, but the clock is not. So on a miss:

```java
if (maybeUser.isEmpty()) {
    passwordEncoder.matches(request.password(), DUMMY_HASH);   // constant-work path
    throw new ApiException(ErrorCode.INVALID_CREDENTIALS, ...);
}
```

`DUMMY_HASH` is a genuine cost-12 BCrypt hash of a value nobody knows, so the
timing matches exactly. The result is discarded; the point is that both paths
pay the same ~250 ms.

**Defence at registration — a documented, accepted leak.** `register` returns
`400 "That email address is already registered."` when the address exists. That
*is* an enumeration oracle, and it is accepted deliberately. The alternative —
accepting silently and emailing the existing owner — is genuinely better and
needs an email pipeline this project does not have. The reasoning for accepting
it: an attacker who can enumerate at *registration* learns which addresses have
accounts; one who can enumerate at *login* learns that **and** gets to test
passwords in the same request. Only the second is worth real UX cost, and only
the second is closed here.

**Residual risk.** Registration enumerates, and it is rate limited (10/min/IP)
rather than prevented. The timing defence is best-effort: `matches()` against a
fixed hash is close but not formally constant-time, and other paths (a DNS
lookup, a cache miss) introduce noise in both directions.

---

## 7. IDOR — reading someone else's booking

**Attack.** Authenticate as yourself, then walk `/api/v1/bookings/{id}` looking
for other people's reservations.

**Defence, in two layers.**

*Unguessable ids.* The URL carries `bookings.public_id`, a random UUID; the
`BIGSERIAL` never leaves the server. Sequential ids invite the probe and also
leak business volume — book twice a day and read the growth rate off the ids.

*Ownership in the WHERE clause, not in an `if`.* The tempting version is:

```java
Booking b = repo.findByPublicId(id).orElseThrow();
if (!b.getUser().getId().equals(currentUserId)) throw new Forbidden();  // easy to forget
```

That works right up until somebody adds a new endpoint and omits the second
line. Instead, `BookingRepository.findOwned(publicId, userId)` puts the owner in
the query:

```java
WHERE b.publicId = :publicId AND b.user.id = :userId
```

so "not found" and "not yours" are the same code path and **there is no check
left to forget**. `BookingController.get` therefore has no `@PreAuthorize` and
no ownership `if` — which is deliberate, not an omission. The unfiltered
`findByPublicId` still exists for the reaper and admin paths, and is named so
that using it in a user-facing handler reads as obviously wrong in review.

**404, not 403.** A 403 confirms that a booking with that id exists, which lets
an attacker map the id space even though they cannot read any of it. Denying
existence leaks nothing. The same reasoning drives `EventService.detail`: a
DRAFT or CANCELLED event returns 404, not a status field, so nobody can read
unannounced line-ups by walking ids.

The seat equivalent is `EventSeatRepository.findForEvent(eventId, seatIds)` —
the `eventId` in the WHERE clause is an authorisation check disguised as a
filter, so a request against event 7 cannot touch a seat belonging to event 8 by
passing its id. And `createHold` reports unknown seat ids as `SEAT_UNAVAILABLE`
rather than `NOT_FOUND`, because two different answers would let a caller map
out which seat ids exist.

**Residual risk.** UUIDs are in URLs, so they land in browser history, in
`Referer` on outbound links (mitigated by
`Referrer-Policy: strict-origin-when-cross-origin` in both `SecurityConfig` and
`nginx.conf`), and in any access log along the path.

---

## 8. Mass assignment

**Attack.** `POST /auth/register {"email":..., "password":..., "role":"ROLE_ADMIN"}`.

**Defence.** `RegisterRequest` **has no `role` field**. There is nothing for
Jackson to bind. The role is decided by the server:
`new User(email, hash, displayName, Role.ROLE_USER)` in `AuthService.register`.
The fix for mass assignment is not to filter the field later — it is to make the
field impossible to send.

This is why request DTOs are separate record types from entities throughout the
codebase. Binding straight onto a JPA entity makes every column a potential
input, including `enabled`, `role` and `version`.

Second layer: `spring.jackson.deserialization.fail-on-unknown-properties: true`.
A client sending an extra field gets `400 MALFORMED_REQUEST` instead of having
it silently dropped. Silently ignoring `"role": "ROLE_ADMIN"` is fine today and
a privilege-escalation bug the day somebody adds a matching field to the DTO.

Third layer, in the database: `CHECK (role IN ('ROLE_USER','ROLE_ADMIN'))`.

**Residual risk.** There is no admin-promotion endpoint at all, so admins are
created by a migration or by hand. That is a scope limitation, not a hole.

---

## 9. SQL injection

**Attack.** `GET /events?q='; DROP TABLE users; --`

**Defence.** Every query in `com.seatlock.repository` is either a derived
Spring Data method name or a `@Query` with **named parameters** bound by JDBC.
There is not one string concatenation into SQL anywhere in the codebase. Bound
parameters are sent to Postgres separately from the statement text, so a value
is never parsed as SQL — it is compared as a literal string and finds nothing,
which is exactly right.

The search query is the one that looks risky and is not:

```java
AND (:q IS NULL OR LOWER(e.title) LIKE LOWER(CONCAT('%', :q, '%')))
```

`CONCAT` here is a **JPQL function evaluated by the database**, not Java string
concatenation. Hibernate emits `LIKE LOWER(('%' || ? || '%'))` with `:q` still a
bound parameter. The `%` wildcards are part of the query text; the user's value
is data.

Two honest notes. A user who types `%` gets a wildcard — that is a search
semantics wart, not an injection. And a leading wildcard cannot use a B-tree
index, so this is a sequential scan; fine at six events, and the honest answer
at 100 000 is a Postgres full-text index (`tsvector` + GIN) or a search engine.

The optional-filter pattern (`:param IS NULL OR ...`) is used precisely so that
dynamic filtering never requires building a SQL string.

**Residual risk.** The pattern relies on discipline: a future contributor
writing `@Query(nativeQuery = true)` with concatenation would reintroduce the
risk. A static analysis step in CI would catch that.

---

## 10. CORS

**Attack.** `evil.com` runs `fetch('https://api.seatlock.example/api/v1/bookings')`
from a victim's browser and reads the response.

**Defence.** `SecurityConfig.corsConfigurationSource()`:

```java
config.setAllowedOrigins(properties.getCors().getAllowedOrigins());  // exact strings
config.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));
config.setAllowedHeaders(List.of("Authorization","Content-Type","Idempotency-Key"));
config.setExposedHeaders(List.of("Retry-After"));
config.setAllowCredentials(false);
config.setMaxAge(3600L);
```

Two things are deliberate:

- **Exact origins, never `setAllowedOriginPatterns("*")`.** A wildcard combined
  with credentials is the classic misconfiguration that lets any site on the
  internet read this API as the logged-in user. There is no wildcard option
  anywhere in this codebase, and `SecurityProperties.Cors.allowedOrigins` is
  `@NotEmpty`, so the application will not start without an explicit list.
- **`setAllowCredentials(false)`.** Authentication is a bearer header, not a
  cookie, so the browser has no ambient credential to attach and we do not need
  to ask for one. Turning it on "just in case" would weaken the rules the
  browser applies.

`OPTIONS /**` is `permitAll()` in the authorisation rules, because a CORS
preflight carries no credentials and must never be challenged — otherwise every
cross-origin call fails before it starts.

Worth being precise about what CORS actually protects: it stops **JavaScript in
another origin from reading the response**. It does not stop the request being
sent. So CORS is not an access control — the bearer token is.

**Residual risk.** In development, `CORS_ALLOWED_ORIGINS` defaults to
`http://localhost:5173`. Getting this list wrong in production is a
configuration mistake with no code-level guard beyond "the list must be
non-empty".

---

## 11. CSRF, and why disabling it is correct here

**Attack.** A malicious page causes the victim's browser to issue a
state-changing request to our API, and the browser attaches the victim's
credentials automatically.

**Defence.** `.csrf(csrf -> csrf.disable())` in `SecurityConfig` — and this is
one of the few places where that is correct rather than lazy. **CSRF exists
because of ambient credentials.** A browser attaches cookies to cross-site
requests on its own; that is the entire mechanism. SeatLock authenticates with
`Authorization: Bearer <jwt>`, which the browser attaches to nothing. A hostile
page can *make* the request, but it cannot add our header — cross-origin
JavaScript cannot set `Authorization` without a preflight that our CORS policy
will refuse. No ambient credential, no CSRF.

**When it must come back.** The moment authentication moves to cookies — which
is exactly what `frontend/README.md` describes as the *better* place for the
refresh token — CSRF protection has to return, along with `SameSite=Lax` or
`Strict`, and a token pattern for the cross-site cases. That trade is why the
frontend has not made that move: it needs a shared parent domain, a certificate
covering both hosts, and CSRF machinery, none of which is a code change.

**Residual risk.** The current design pays for "no CSRF" with "refresh token in
`localStorage`", which is stealable by XSS. That is a real trade, not a free
win, and section 18 says so.

---

## 12. Rate limiting

**Attack.** Credential stuffing against `/auth/login` (each attempt costs us
250 ms of BCrypt, so it is also a cheap DoS), and seat-sweeping scripts against
`POST /events/{id}/holds` during a drop.

**Defence.** `config/RateLimitFilter.java`, a Bucket4j token bucket per client
IP: **10/minute** for `/auth/login`, `/auth/register`, `/auth/refresh`, and
**30/minute** for `POST /api/v1/events/*/holds`. Everything else is unlimited
and relies on upstream infrastructure.

**Token bucket, not fixed window.** A fixed-window counter ("100 per minute")
has a boundary problem: send 100 at 11:59:59 and 100 at 12:00:00 and you pass
every check while actually sending 200 requests in one second.
`Bandwidth.simple(perMinute, Duration.ofMinutes(1))` refills continuously — ten
tokens a minute means one token every six seconds — so a small burst is allowed
(real users do click twice) but the sustained rate is genuinely capped with no
window to game. `tryConsumeAndReturnRemaining` is used rather than `tryConsume`
so the 429 can carry an accurate `Retry-After`; telling a client exactly when to
come back is what stops a well-behaved one from retrying in a tight loop and
making things worse.

**Filter ordering is part of the defence.** The limiter is registered with
`.addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)`
and runs before any authentication work, for the reason above.

**The bounded cache is a security control, not a tuning knob.** The bucket map
is keyed by client IP, which an attacker partly controls. An unbounded
`ConcurrentHashMap` would grow one entry per distinct source address until the
heap ran out — turning the anti-abuse component into an amplifier for the abuse
it prevents. So the store is Caffeine with `maximumSize(100_000)` and
`expireAfterAccess(10, MINUTES)`. Ten idle minutes means a full refill has
happened anyway, so an evicted entry carries no information worth keeping.

**The `X-Forwarded-For` trap.** `clientKey()` uses
`request.getRemoteAddr()` and **never reads the header directly**, because that
header is client-supplied: anyone can send `X-Forwarded-For: 1.2.3.4` and get a
fresh budget on every request, which defeats the limiter entirely and also lets
them frame an innocent address. Instead `server.forward-headers-strategy:
framework` installs Spring's `ForwardedHeaderFilter`, which parses the header —
**and that is only safe if the proxy in front of us overwrites the header rather
than appending to whatever the client sent.** This is a hard requirement on the
deployment, spelled out in `07-deployment.md`. A rate limiter is only as
trustworthy as the proxy configuration underneath it.

**The honest limitation.** These buckets live in this JVM's heap. Run three
instances behind a load balancer and the effective limit is 3× what is
configured, because each instance counts independently. That is a documented
trade-off, not an oversight. The fix is Bucket4j's Redis backend — the same
`Bandwidth` definitions with a `LettuceBasedProxyManager` instead of an
in-memory map, using the Redis this project already runs. The reason not to do
it here is that it puts a Redis round trip in front of every request, including
the ones that will pass. Knowing which limitation you have chosen is the point.

**Residual risk.** Per-IP limiting punishes shared NAT (an office, a campus, a
mobile carrier) and is trivially evaded by a botnet or a rotating proxy pool.
There is no per-account limit, no progressive lockout, and no CAPTCHA.

---

## 13. Error handling and information disclosure

**Attack.** Trigger an unhandled exception and read the internals out of the
response: table names, column names, file paths, library versions, sometimes
values from other users' rows. "Show the exception message on error" is one of
the most common information-disclosure findings in a web application pentest.

**Defence.** `web/GlobalExceptionHandler.java`, built around one rule:
**anticipated failures are described precisely, unanticipated ones are not
described at all.**

- An `ApiException` was thrown on purpose by code that knew what it meant, so
  its message is safe and specific and is returned.
- Everything else — `NullPointerException`, a driver error, a failed cast — is a
  bug. `handleUnexpected` logs the whole thing with a 16-hex-character trace id
  and returns `500 INTERNAL_ERROR` carrying **only** that id. The user can quote
  it to support; support can grep the log for it.

Specific cases:

- `HttpMessageNotReadableException` → `400 MALFORMED_REQUEST` with the
  framework's message discarded, because it quotes the offending JSON and the
  target Java class.
- `MethodArgumentTypeMismatchException` names the parameter (already public in
  the URL template) but never echoes the value.
- `DataIntegrityViolationException` → `409 SEAT_UNAVAILABLE`. The most likely
  cause is `booking_seats_one_active_per_seat` doing its job, and from the
  user's point of view the seat was taken. `ex.getMostSpecificCause().getMessage()`
  is logged but never returned, because Postgres constraint messages quote the
  conflicting values, which can be another user's data.
- `OptimisticLockingFailureException` → `409 CONCURRENT_MODIFICATION`, logged at
  **INFO**: reaching it means the Redis hold did not filter the conflict, so a
  sudden burst of these is a real signal that Redis is unhealthy.
- Log level follows the status class, not the fact that an exception occurred. A
  user losing a contested seat is a 409 and completely normal; logging it at
  WARN would bury real problems under noise during exactly the traffic spike
  where you need the logs readable.

`SecurityConfig` also writes the same JSON envelope from inside the filter
chain, because 401s and 403s happen *before* a controller is chosen and
`@RestControllerAdvice` never sees them. Without that, a 401 would come back as
Spring's default HTML error page while every other error was JSON, and the
frontend's error parser would break on exactly the response it most needs to
understand.

Backing this up in `application.yml`: `server.error.include-message: never`,
`include-stacktrace: never`, `include-binding-errors: never`.

**Residual risk.** Response *timing* and status codes still leak a little (see
section 6). Trace ids are random per error and are not correlated across a
request chain — a real deployment would use a propagated trace id from
Micrometer Tracing / OpenTelemetry instead.

---

## 14. Secrets management

**Attack.** A credential is committed to git, or a service boots with a default
password because someone forgot an environment variable.

**Defence.** One rule, applied in `application.yml`: **no secret has a default
value.** The syntax is `${JWT_SECRET}` with no `:-fallback`. If the variable is
missing the application refuses to start. That is deliberate — a service that
silently boots with the password `postgres` because an env var was missing is
how test credentials reach production. Non-secret values (pool sizes, TTLs) do
get sensible defaults.

The dev profile is the one place with literal values, and the committed dev JWT
secret is worthless on purpose: it only ever signs tokens for a local database
full of fake seats. `application-prod.yml` *tightens* rather than supplies —
notably `spring.data.redis.password: ${REDIS_PASSWORD}` with **no** fallback,
overriding the base file's `${REDIS_PASSWORD:}`, so a passwordless production
Redis cannot boot.

`.gitignore` opens with the secrets section: `.env`, `.env.*` (with
`!.env.example` re-included), `*.pem`, `*.p12`, `*.jks`, `**/secrets/`. A
committed `.env` is the most common way a student project leaks credentials.
`.env.example` is committed so a new developer knows exactly which variables
exist without ever seeing a value.

On the frontend, only `VITE_`-prefixed variables reach the bundle — a
deliberate safety rail, because a variable without the prefix simply is not
substituted. `frontend/.env.example` says plainly that everything in that file
ships to the browser.

**Residual risk.** Secrets arrive as environment variables, which are visible in
`/proc/<pid>/environ` to anyone on the box and are often captured in crash
dumps and process listings. There is no rotation story. `07-deployment.md`
covers AWS Secrets Manager / SSM Parameter Store as the production answer.

---

## 15. Security response headers

Set in `SecurityConfig.headers(...)` for the API, and again in
`frontend/nginx.conf` for the static site (with `always`, so they are present on
4xx and 5xx responses too — an error page is exactly where injected content
tends to end up).

| Header | Stops |
|---|---|
| `X-Content-Type-Options: nosniff` | the browser second-guessing our `Content-Type` and executing a JSON response as HTML |
| `X-Frame-Options: DENY` | clickjacking — SeatLock loaded in a transparent iframe over an attacker's page. DENY rather than SAMEORIGIN because nothing here ever frames itself |
| `Referrer-Policy: strict-origin-when-cross-origin` | leaking full URLs (which contain booking UUIDs) in the `Referer` of outbound links |
| `Strict-Transport-Security: max-age=31536000; includeSubDomains` | protocol downgrade — after one visit the browser refuses plaintext HTTP for a year |
| `Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=()` (nginx) | injected script reaching hardware APIs |
| `Content-Security-Policy` (nginx) | the real XSS mitigation — see below |

The frontend CSP is worth reading in full in `nginx.conf`. The load-bearing part
is `script-src 'self'` with **no** `'unsafe-inline'` and **no** `'unsafe-eval'`:
Vite emits real `.js` files, so neither is needed, and their absence is what
makes this CSP an actual mitigation rather than decoration — an injected
`<script>` tag simply will not run. `style-src` does allow `'unsafe-inline'`,
which is the one concession: React sets inline `style` attributes (the seat
curve transform, the poster palette) and CSP treats those as inline styles.
Inline style is a far weaker vector — it cannot execute code — so it is an
acceptable trade. Also `frame-ancestors 'none'`, `base-uri 'self'` (stops
injected markup rewriting `<base href>` to point every relative URL at an
attacker), `form-action 'self'`, and `object-src 'none'`.

**Residual risk.** The API itself sends no CSP — it returns JSON, so there is
nothing to inject into, but a defence-in-depth `Content-Security-Policy:
default-src 'none'` on API responses would cost nothing. HSTS is emitted but
means nothing until TLS actually terminates in front of the app.

---

## 16. Actuator endpoint exposure

**Attack.** `/actuator/env` dumps every configuration value including
credentials; `/actuator/heapdump` hands over a file containing live memory —
tokens, passwords in flight, other users' data. Both are catastrophic and both
are one config line away by default.

**Defence.** `application.yml` exposes exactly three:
`include: health,info,prometheus`. `application-prod.yml` narrows further to
`health,prometheus` and sets `endpoint.health.show-details: never` (the base
profile uses `when-authorized`). In `SecurityConfig`:

```java
.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
.requestMatchers("/actuator/**").hasRole("ADMIN")
```

Health is public because a load balancer probes it and cannot present a token.
Everything else — including `/actuator/prometheus`, which carries traffic shape
and error rates — requires ROLE_ADMIN.

**Residual risk (and one thing to fix).** The Swagger UI and OpenAPI JSON are
`permitAll()`:

```java
.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
```

That is a deliberate choice for a portfolio project meant to be explored, and it
is a free map of the attack surface in a real deployment. `07-deployment.md`
notes it should be restricted or disabled there. Metrics scraping over
ROLE_ADMIN also means Prometheus needs an admin token, which is workable but
unusual — network-level restriction (a security group allowing only the scraper)
is the more normal answer.

---

## 17. Input validation and request-shaped DoS

**Attack.** `GET /events?size=100000` makes the database materialise every row
and the JVM hold them all. `POST /holds {"seatIds": [1..10000]}` builds a
10 000-key Lua script and blocks Redis's single command-execution thread while
it runs. A megabyte password burns CPU in BCrypt.

**Defence.** Bean validation at the edge, enforced (`@Validated` on the
controllers is what makes `@Min`/`@Max` on query parameters actually run —
without it they are decoration):

| Limit | Where | Value |
|---|---|---|
| page size | `EventController.browse`, `BookingController.list` | `@Min(1) @Max(50)` |
| seats per hold | `CreateHoldRequest` + `HoldProperties.maxSeatsPerBooking` | 10 (annotation) and 10 (configurable, re-checked in `BookingService.createHold`) |
| password length | `RegisterRequest` | 10–128 |
| email length | `RegisterRequest`, `LoginRequest` | 320 (the RFC max) |
| idempotency key | `BookingController.confirm` | `@NotBlank @Size(max = 120)` |
| payment token | `ConfirmBookingRequest` | `@Pattern("^[A-Za-z0-9_\\-]{8,200}$")` |
| form post size | `application.yml` | `max-http-form-post-size: 256KB` |
| DB connection wait | `application.yml` | `connection-timeout: 3000` — fail fast rather than queueing forever when the DB is sick |

The seat cap is duplicated on purpose: the annotation gives a clean 400 with a
helpful message before any of our code runs, and the service check is what
enforces the *configured* business rule. `HoldProperties` and
`SecurityProperties` are `@Validated @ConfigurationProperties`, so a typo in a
property name or a TTL of zero fails the application context at boot rather than
at 9pm when the first user clicks a seat.

The payment token pattern is worth noting: the token is opaque to us, but
"opaque" must not mean "anything at all" — constraining the character set closes
off injection into whatever consumes it downstream.

**Residual risk.** There is no global request-size limit on JSON bodies beyond
Tomcat's defaults, no per-account concurrency limit, and no protection against a
slowloris-style connection exhaustion attack (that belongs at the load balancer).

---

## 18. Client-side: XSS and token storage

**Attack.** Script injected into the SPA reads the user's tokens and uses them
from anywhere.

**Defence.** Three things, and one honest concession.

- **No `dangerouslySetInnerHTML` anywhere.** React escapes interpolated strings
  by default, so an event title containing `<script>` renders as literal text.
  That is why this project needs no HTML sanitiser.
- **CSP `script-src 'self'`** (section 15) means an injected `<script>` tag does
  not execute even if markup injection is achieved.
- **The access token lives in memory only** — a module-level variable in
  `frontend/src/lib/tokenStore.ts`, never in web storage. Injected script cannot
  read a closure variable it has no reference to. That is not a complete defence
  (script on the page can just call our own `apiClient` and act as the user) but
  it removes the *durable* theft case where a token is exfiltrated and reused
  later from another machine. Cost: a page refresh loses it, so the app silently
  re-mints one from the refresh token on boot.

**The concession.** The refresh token lives in `localStorage`, and it **is**
stealable by XSS — and it is the more valuable of the two, because it lives 7
days. The correct fix is not `sessionStorage` or encrypting the value; both are
theatre, since injected script runs in our origin and can do anything our code
can. The correct fix is an **httpOnly, Secure, SameSite cookie** set by the
server, which script cannot read because the browser enforces it. This project
does not get that for free: the frontend is static files on S3/CloudFront and
the API is on EC2 — two origins. A cross-origin cookie needs `SameSite=None;
Secure`, which reintroduces CSRF (section 11) and needs a shared parent domain
and a certificate covering both hosts. That is infrastructure work, not a code
change. So the weaker design was chosen knowingly; the mitigations in place are
that the access token is never persisted, that refresh tokens rotate and a
replay revokes the whole chain, and that no server string is ever rendered as
HTML.

---

## 19. Payment data

**Attack.** Card numbers in logs, in the database, in a backup.

**Defence.** `ConfirmBookingRequest` has no card fields. The client exchanges
the card with the payment provider directly and sends only a single-use token.
Card data never touches this server, its logs, its database or its backups —
which is both the correct design and the reason a system like this stays outside
the most demanding parts of PCI-DSS scope. `StubPaymentGateway` never logs the
token, because it is a bearer credential for a charge.

The `authorize` → commit → `capture` ordering (rather than charge-and-refund) is
a correctness decision rather than a security one and is covered in
`04-booking-lifecycle.md`.

**Residual risk.** The gateway is a stub. `StubPaymentGateway` is a plain
`@Component` with no profile condition, so it is active **everywhere including
production** — deliberately, and named so nobody can mistake it for real, but it
means this system cannot take money.

---

## What a real production deployment would add

Honest and specific. None of these are in the repository.

1. **TLS everywhere, terminated at an ALB or CloudFront**, with the certificate
   from ACM and HTTP redirected to HTTPS. Today HSTS is emitted by an
   application that is perfectly happy to serve plaintext.
2. **A real payment provider** behind the `PaymentGateway` interface, with
   webhook handling for asynchronous settlement, and the stub moved behind
   `@Profile("stub-payments")`.
3. **A reconciliation job** for the one genuinely bad failure window in
   `confirm` (booking committed, capture not reached) — an outbox table written
   in the same transaction as the booking, drained by a worker, plus a nightly
   settlement comparison against the provider. See `04-booking-lifecycle.md`.
4. **Distributed rate limiting** via Bucket4j's Redis backend, plus per-account
   limits and progressive lockout on repeated failed logins, plus a CAPTCHA or
   proof-of-work on registration.
5. **Secrets from AWS Secrets Manager or SSM Parameter Store** with automatic
   rotation, injected at runtime rather than as static environment variables,
   and a JWT signing key with a `kid` header so keys can overlap during
   rotation.
6. **Multi-factor authentication**, a breached-password check at registration
   (HIBP k-anonymity), account lockout, and an email notification when a session
   is revoked for a security reason.
7. **Email verification** at registration, which would also let registration
   stop leaking whether an address exists.
8. **A WAF** (AWS WAF or Cloudflare) in front, for the request-shaped attacks
   the application cannot see: slowloris, volumetric floods, known bad bots.
9. **Audit logging** of security-relevant events (login, logout, token reuse
   detection, admin actions) to an append-only store separate from application
   logs, with retention.
10. **Structured logging with a propagated trace id** (Micrometer Tracing /
    OpenTelemetry) instead of per-error random ids, shipped to CloudWatch or an
    ELK stack, with alerts on `TOKEN_REUSE_DETECTED` and on bursts of
    `CONCURRENT_MODIFICATION`.
11. **Dependency and container scanning in CI** — `mvn dependency-check`,
    `npm audit`, Trivy on the images — plus Dependabot. There is no such gate
    today.
12. **Swagger UI restricted or disabled** in production, and `/actuator/prometheus`
    restricted at the network layer rather than by role.
13. **Backups with a tested restore**, point-in-time recovery on RDS, and
    encryption at rest (KMS) for both RDS and ElastiCache.
14. **The refresh token moved to an httpOnly cookie**, with the domain and
    certificate work that requires, and CSRF protection turned back on.
