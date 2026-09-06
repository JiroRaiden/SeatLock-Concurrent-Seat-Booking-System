/**
 * Where the two tokens live, and the honest trade-off behind that choice.
 *
 * ACCESS TOKEN — in memory only (the module-level variable below).
 *   It is never written to localStorage, sessionStorage, or a cookie. If a
 *   cross-site scripting bug ever lands in this app, injected script can read
 *   anything in web storage, but it cannot read a closure variable it has no
 *   reference to. That is not a complete defence — script running on the page
 *   can simply call our own `apiClient` and act as the user — but it removes
 *   the *durable* theft case, where an attacker exfiltrates a token and reuses
 *   it later from their own machine. Cost: a page refresh loses the access
 *   token, so we silently re-mint one from the refresh token on boot.
 *
 * REFRESH TOKEN — localStorage.
 *   This is the compromise, and it is worth being straight about it rather than
 *   dressing it up. A refresh token in localStorage IS stealable by XSS, and it
 *   is the more valuable of the two because it is long-lived (7 days here).
 *
 *   The correct fix is not "use sessionStorage" or "encrypt it" — both are
 *   theatre, since injected script runs in the same origin and can do whatever
 *   our code can do. The correct fix is an httpOnly, Secure, SameSite cookie
 *   set by the server: script cannot read it at all, because the browser
 *   enforces that, not us.
 *
 *   We do not get that for free in this project's deployment. The frontend is
 *   static files on S3/CloudFront and the API is Spring Boot on EC2 — two
 *   different origins. A cross-origin cookie has to be `SameSite=None; Secure`,
 *   which means it is attached to every request the API receives from anywhere,
 *   which means we now need CSRF protection we did not previously need, plus a
 *   shared parent domain and a TLS certificate covering both hosts. That is a
 *   real infrastructure change, not a one-line code change.
 *
 *   So the honest statement is: this is the weaker of two designs, chosen
 *   because the stronger one needs deployment work outside this repo's scope.
 *   The mitigations actually in place are (a) the access token is never
 *   persisted, (b) refresh tokens rotate on every use and the backend revokes
 *   the entire chain on replay (`TOKEN_REUSE_DETECTED`), so a stolen token has
 *   a short useful life and its use is detectable, and (c) we never render
 *   server strings as HTML, which is the XSS vector this would depend on.
 */

const REFRESH_TOKEN_KEY = 'seatlock.refreshToken';

/** Deliberately module-private: nothing outside this file holds the reference. */
let accessTokenInMemory: string | null = null;

export function getAccessToken(): string | null {
  return accessTokenInMemory;
}

export function setAccessToken(token: string | null): void {
  accessTokenInMemory = token;
}

export function getRefreshToken(): string | null {
  try {
    return window.localStorage.getItem(REFRESH_TOKEN_KEY);
  } catch {
    // localStorage throws (not returns null) in Safari private mode and when a
    // browser is configured to block site data. An auth store is not worth
    // crashing the whole app over — degrade to "logged out" instead.
    return null;
  }
}

export function setRefreshToken(token: string | null): void {
  try {
    if (token === null) {
      window.localStorage.removeItem(REFRESH_TOKEN_KEY);
    } else {
      window.localStorage.setItem(REFRESH_TOKEN_KEY, token);
    }
  } catch {
    // Same reasoning as above: the session simply will not survive a refresh.
  }
}

export function clearTokens(): void {
  setAccessToken(null);
  setRefreshToken(null);
}
