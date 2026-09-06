# SeatLock — frontend

React + TypeScript client for SeatLock, a concurrent seat-booking system.
It talks to the Spring Boot API documented in [`../docs/API.md`](../docs/API.md),
which is a fixed contract: this app conforms to it and does not invent
endpoints or rename fields.

---

## Running it

```bash
npm install
npm run dev            # http://localhost:5173
```

The dev server proxies `/api` to `http://localhost:8080`, so start the backend
first. Because the proxy makes API calls same-origin, local development has no
CORS preflights and behaves the way production does behind nginx.

```bash
npm run typecheck      # tsc, no emit — strict + noUncheckedIndexedAccess
npm run lint           # eslint, zero warnings tolerated
npm run build          # typecheck, then a production bundle into dist/
npm run preview        # serve the built bundle locally
```

### Configuration

Copy `.env.example` to `.env.local`. There is one variable:

| Variable | Meaning |
|---|---|
| `VITE_API_BASE_URL` | Origin of the API, **without** `/api/v1`. Leave **empty** in development so requests go same-origin through the Vite proxy. Set it only when the API is on a different host from the static files. |

Only `VITE_`-prefixed variables reach the browser bundle. That prefix is a
safety rail — a variable without it cannot leak into client-side JavaScript by
accident. Never put a secret in this file; everything in it ships in the bundle.

### Docker

```bash
docker build -t seatlock-frontend .
docker run -p 8081:80 seatlock-frontend
```

A multi-stage build: stage one has Node and the full dependency tree, stage two
is nginx plus a folder of static files. Only stage two ships, so the running
image contains no compiler, no npm and no source. `nginx.conf` does SPA history
fallback and sets the security headers (each one commented in that file).

---

## Folder layout

```
src/
├── api/
│   ├── schemas.ts        zod schemas — the runtime shape of every response
│   └── endpoints.ts      one function per endpoint in API.md, nothing more
├── auth/
│   ├── AuthContext.tsx   who is signed in; boots by spending the refresh token
│   └── RequireAuth.tsx   route guard for checkout and bookings
├── components/           shared UI
│   ├── PosterArt.tsx     procedural poster + wide backdrop generator
│   ├── Header.tsx        sticky chrome: wordmark, city, search, account
│   ├── Rail.tsx          horizontal snap-scrolling strip
│   ├── EventCard.tsx     one poster card
│   ├── Countdown.tsx     the hold clock (hook + display)
│   └── states.tsx        skeleton / error / empty
├── features/seatmap/     everything specific to the auditorium
│   ├── geometry.ts       the row-curve maths
│   ├── Seat.tsx          one seat button, memoised
│   ├── ScreenArc.tsx     "SCREEN THIS WAY"
│   ├── Legend.tsx        the key
│   └── SelectionBar.tsx  sticky summary + Proceed
├── lib/
│   ├── apiClient.ts      the only fetch wrapper: auth, errors, refresh mutex
│   ├── tokenStore.ts     where the two tokens live, and why
│   ├── hash.ts           FNV-1a + mulberry32, the poster seed
│   └── format.ts         money, dates, durations, the countdown string
├── pages/                one file per route
└── styles/
    ├── theme.css         design tokens — the only file with raw hex values
    └── index.css         Tailwind layers + a few component classes
```

Routes: `/` browse · `/events/:id` · `/events/:id/seats` · `/checkout/:holdId`
· `/bookings` · `/bookings/:id` · `/login` · `/register`.

---

## Design decisions

### The seat-map curve

A real auditorium's rows are arcs, not straight lines — seats bow away from the
screen so every seat faces it. Flat rows are the single thing that makes a seat
map look like a spreadsheet, so `features/seatmap/geometry.ts` reproduces the
arc.

For a seat at column `c` in a row spanning `[min, max]`:

```
centre = (min + max) / 2
half   = (max - min) / 2
t      = (c - centre) / half     →  -1 at the left end, 0 in the middle, +1 at the right
dy     = depth * t²              →  pixels to push this seat DOWN the page
```

The `t²` term is doing all the work. Squaring makes the displacement **zero at
the centre** (the middle seat does not move), **symmetric** (both ends drop by
the same amount — a linear `t` would tilt the row instead of bowing it), and
**slow near the middle, fast at the edges**, which is the shape of a parabola.
Over the ~40° a cinema row actually subtends, a parabola and a circular arc are
visually indistinguishable, and the parabola costs one multiply per seat instead
of a trig call.

`depth` is interpolated from 8px on the front row to 26px on the back row,
because rows further from the screen subtend more of it and bow more.

Two implementation details that matter:

- The offset is applied as `transform: translateY(...)`, **not** as a margin or
  a grid row. A transform does not affect layout, so the CSS grid keeps its
  clean rectangular structure and the curve is purely a paint-time effect on
  top of it.
- Seats are placed with `gridColumn: seat.colIndex` and nothing else. The API
  already bakes the centre-aisle gap into `colIndex`, so the aisle is simply a
  grid column with no seat in it — no special-casing, no spacer elements.

### The procedural poster system

The API returns `posterUrl: null` and there is no image CDN. Grey placeholders
look unfinished and external stock photos break, so the artwork is **generated
from the title**.

`hashString` (FNV-1a, 32-bit) turns the title into a stable integer. That seed
picks one of 7 hand-authored palettes and one of 6 geometric treatments
(diagonal split, concentric arcs, offset line grid, halftone field, stacked
bands, off-canvas circle), and seeds a small PRNG (mulberry32) that supplies
every offset and rotation inside the chosen treatment. The result is drawn as
inline SVG at 2:3, with the title word-wrapped in Bebas Neue across up to three
tightly-leaded lines in the bottom-left, over a bottom-up scrim that guarantees
the type is readable whatever the geometry did. An `feTurbulence` grain layer at
13% opacity sits over everything — that grain is the cheapest part and the
difference between "generated SVG" and "printed poster".

Two properties this buys:

- **Deterministic.** The same title always produces the same poster, on every
  device, forever. Users recognise a film by its artwork, which is a poster's
  entire job — and `BackdropArt` reuses the same identity at a 1600×520 ratio,
  so an event's detail page is visibly the same artwork as the rail card the
  user clicked.
- **Varied.** Palette and treatment are derived from *different bit ranges* of
  the hash (`seed % 7` and `(seed >>> 8) % 6`). Using the same raw number for
  both would correlate them, so certain palettes would only ever appear with
  certain shapes. Chi-square over 900 sample titles: 2.60 for treatments (df=5)
  and 4.01 for palettes (df=6) — both comfortably within chance.

Palettes are curated rather than generated from random hues, because a
random-hue generator reliably produces a few muddy combinations, and one ugly
poster in a rail undoes the other five.

### Token storage — the trade-off, stated honestly

The **access token lives in memory only** (a module variable in
`lib/tokenStore.ts`), never in web storage. Injected script cannot read a
closure variable it has no reference to, which removes the *durable* theft case
where a token is exfiltrated and reused later from another machine.

The **refresh token lives in `localStorage`**, and this is the weaker half of
the design. A refresh token in localStorage **is** stealable by XSS, and it is
the more valuable of the two because it is long-lived.

The correct fix is not sessionStorage or encrypting the value — both are
theatre, since injected script runs in our origin and can do anything our code
can. The correct fix is an **httpOnly, Secure, SameSite cookie** set by the
server, which script cannot read because the browser enforces that.

This project does not get that for free. The frontend is static files on
S3/CloudFront and the API is Spring Boot on EC2 — two different origins. A
cross-origin cookie needs `SameSite=None; Secure`, which means it rides along on
every request the API receives from anywhere, which means CSRF protection we did
not previously need, plus a shared parent domain and a certificate covering both
hosts. That is an infrastructure change, not a code change.

So: the weaker design was chosen because the stronger one needs deployment work
outside this repo. The mitigations actually in place are that the access token
is never persisted, that refresh tokens rotate on every use and the backend
revokes the whole chain on replay (`TOKEN_REUSE_DETECTED`) so a stolen token has
a short life and its use is detectable, and that no server string is ever
rendered as HTML.

### Other decisions worth knowing

**Seat clicks make no network call.** Selection is local React state until the
user presses Proceed, which fires one `POST /events/{id}/holds` for the whole
set. Locking on every tap would generate thousands of reservations per second on
a popular release, nearly all abandoned seconds later. The cost is that the map
can go stale, and the server answers that honestly with a `409` listing exactly
which seat ids were lost — the handler prunes those from the selection, refetches
the map, and flashes the lost seats in the accent colour so the user's eye goes
straight to what changed.

**One refresh, not ten.** `lib/apiClient.ts` holds the in-flight refresh promise
in a module variable. Ten parallel 401s all await the same promise, so one
refresh happens instead of ten. This is not just efficiency: the backend rotates
refresh tokens and treats a replayed one as an attack, so the naive
"refresh on every 401" implementation would log the user out precisely when the
app is busiest. A single mutable variable is safe here because JavaScript is
single-threaded — no code can run between the null check and the assignment.

**One idempotency key per checkout attempt**, held in a `useRef` so retries
reuse it. If the network drops after the server committed the booking but before
the response arrived, retrying with the same key replays the original response;
a fresh key would charge the user twice.

**No `dangerouslySetInnerHTML` anywhere.** React escapes interpolated strings by
default, so a title containing `<script>` renders as literal text. That is why
this project needs no HTML sanitiser.

**No raw Tailwind palette.** `tailwind.config.ts` *replaces* `theme.colors`
rather than extending it, so `bg-slate-900` does not exist as a class. Every
colour resolves to a CSS custom property in `theme.css`, which is the only file
in the project containing a hex value — and inline SVG and keyframes read the
same variables the utility classes do.

One sharp edge worth recording: Tailwind **cannot** apply an opacity modifier to
a colour defined as a bare `var()` (`bg-ink-950/90` silently emits no CSS at
all, so the element ends up fully transparent). The few translucent surfaces use
explicit `--scrim-*` tokens instead.

**Density is the design.** Body text is 13px, labels are 11px uppercase at
0.08em tracking, radii cap at 8px, and surfaces are separated by 1px borders
rather than shadows or blur. Inter is loaded with `font-feature-settings:
'tnum' 1` so digits are monospaced — without it the checkout countdown visibly
jitters as it ticks, because a "1" is narrower than an "8".
