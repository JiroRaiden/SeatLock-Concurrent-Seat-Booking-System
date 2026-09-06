import { z } from 'zod';
import { apiErrorBodySchema, authResponseSchema } from '@/api/schemas';
import {
  clearTokens,
  getAccessToken,
  getRefreshToken,
  setAccessToken,
  setRefreshToken,
} from '@/lib/tokenStore';

/**
 * The single HTTP entry point for the whole app.
 *
 * Everything that talks to the backend goes through `request()`. Centralising
 * it buys four things that would otherwise be copy-pasted (and eventually
 * copy-pasted wrong) into every call site:
 *   1. the bearer token gets attached,
 *   2. the standard error envelope from API.md becomes a typed `ApiError`,
 *   3. a 401 triggers exactly one refresh-and-retry, and
 *   4. the response body is validated by zod before any component sees it.
 */

/**
 * In dev this is empty, so requests go to a same-origin `/api/v1/...` path and
 * Vite's proxy forwards them to Spring on :8080. In a container build it is the
 * public API origin baked in at build time by Vite's define-time substitution.
 */
const API_ORIGIN = import.meta.env.VITE_API_BASE_URL ?? '';
const API_PREFIX = '/api/v1';

/* --------------------------------------------------------------- errors -- */

/**
 * A typed error carrying the API.md envelope.
 *
 * Components branch on `code` (a stable contract string) rather than on
 * `message` (human prose that will change) or on `status` alone (409 covers
 * five different situations). `isApiError` is a type guard because a `catch`
 * block's binding is `unknown` under strict mode, so we must narrow it before
 * touching `.code`.
 */
export class ApiError extends Error {
  readonly code: string;
  readonly status: number;
  readonly traceId: string | undefined;
  readonly fieldErrors: Record<string, string> | undefined;
  readonly details: Record<string, unknown> | undefined;
  /** Seconds to wait, from the Retry-After header on a 429. */
  readonly retryAfterSeconds: number | undefined;

  constructor(init: {
    code: string;
    message: string;
    status: number;
    traceId?: string | undefined;
    fieldErrors?: Record<string, string> | undefined;
    details?: Record<string, unknown> | undefined;
    retryAfterSeconds?: number | undefined;
  }) {
    super(init.message);
    this.name = 'ApiError';
    this.code = init.code;
    this.status = init.status;
    this.traceId = init.traceId;
    this.fieldErrors = init.fieldErrors;
    this.details = init.details;
    this.retryAfterSeconds = init.retryAfterSeconds;
  }

  /**
   * Pulls `details.unavailableSeatIds` out in a type-safe way. The envelope
   * types `details` as an open record of unknowns, so this is where we do the
   * one narrowing cast — checking every element is a number rather than
   * trusting the shape.
   */
  get unavailableSeatIds(): number[] {
    const raw = this.details?.['unavailableSeatIds'];
    if (!Array.isArray(raw)) return [];
    return raw.filter((value): value is number => typeof value === 'number');
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError;
}

/** A network failure (offline, DNS, CORS) never reaches the server at all. */
function networkError(): ApiError {
  return new ApiError({
    code: 'NETWORK_ERROR',
    message: 'Could not reach SeatLock. Check your connection and try again.',
    status: 0,
  });
}

/* -------------------------------------------------------- refresh mutex -- */

/**
 * THE MUTEX.
 *
 * The problem it solves: the Browse screen, the header's "me" query and a
 * bookings prefetch can all be in flight when the 15-minute access token
 * expires. All three come back 401 within a few milliseconds of each other. If
 * each one independently called POST /auth/refresh, we would fire three
 * refreshes with the same refresh token.
 *
 * That is not merely wasteful — it is actively destructive here, because this
 * backend ROTATES refresh tokens and treats a replayed one as an attack
 * (`TOKEN_REUSE_DETECTED`, which revokes the entire token chain). The first
 * refresh consumes the token and returns a new one; the second presents the
 * now-dead token and gets the user forcibly logged out of every session. So
 * the naive "just refresh on 401" implementation logs users out precisely when
 * the app is busiest.
 *
 * The fix is this one module-level variable. It holds the in-flight refresh
 * promise. The first caller to find it `null` starts the refresh and stores the
 * promise; callers two and three find it non-null and simply `await` the same
 * promise. One network call, one rotation, three retries. The variable is
 * cleared in a `finally` so a failed refresh does not wedge the app forever.
 *
 * A single mutable module variable is safe here because JavaScript is
 * single-threaded: no other code can run between the `if (x === null)` check
 * and the assignment on the next line, so there is no interleaving to guard
 * against. In a threaded language this would need a real lock.
 */
let inFlightRefresh: Promise<boolean> | null = null;

async function performRefresh(): Promise<boolean> {
  const refreshToken = getRefreshToken();
  if (refreshToken === null) return false;

  try {
    const response = await fetch(`${API_ORIGIN}${API_PREFIX}/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
    });

    if (!response.ok) {
      // The refresh token is dead (expired, revoked, or replayed). There is no
      // recovery path except a fresh login, so drop both tokens.
      clearTokens();
      return false;
    }

    const parsed = authResponseSchema.safeParse(await response.json());
    if (!parsed.success) {
      clearTokens();
      return false;
    }

    setAccessToken(parsed.data.accessToken);
    // Rotation: the token we just used is dead, so we must store the new one.
    setRefreshToken(parsed.data.refreshToken);
    return true;
  } catch {
    return false;
  }
}

/** The gate every 401 handler goes through. */
function refreshOnce(): Promise<boolean> {
  if (inFlightRefresh === null) {
    inFlightRefresh = performRefresh().finally(() => {
      inFlightRefresh = null;
    });
  }
  return inFlightRefresh;
}

/**
 * Lets the auth bootstrap reuse the same single-flight refresh on page load,
 * so a refresh on boot and a 401 from an early query still collapse into one.
 */
export function refreshSession(): Promise<boolean> {
  return refreshOnce();
}

/**
 * Called when a refresh fails and the user must be treated as logged out.
 * AuthContext registers a handler so the client can clear React state without
 * this module importing React (which would make it untestable and circular).
 */
type SessionExpiredHandler = () => void;
let onSessionExpired: SessionExpiredHandler | null = null;
export function setSessionExpiredHandler(handler: SessionExpiredHandler | null): void {
  onSessionExpired = handler;
}

/* -------------------------------------------------------------- request -- */

interface RequestOptions<TSchema extends z.ZodTypeAny> {
  method?: 'GET' | 'POST' | 'DELETE' | 'PUT' | 'PATCH';
  /** Serialised as JSON. Omit for GET/DELETE. */
  body?: unknown;
  /** Extra headers, e.g. the required `Idempotency-Key` on confirm. */
  headers?: Record<string, string>;
  /** Response validator. Omit for 204-returning endpoints. */
  schema?: TSchema;
  /** Public endpoints skip the Authorization header and the refresh dance. */
  auth?: boolean;
  signal?: AbortSignal;
}

async function parseErrorResponse(response: Response): Promise<ApiError> {
  const retryAfterHeader = response.headers.get('Retry-After');
  const retryAfterSeconds =
    retryAfterHeader !== null && retryAfterHeader.trim() !== ''
      ? Number(retryAfterHeader)
      : undefined;

  let body: unknown = null;
  try {
    body = await response.json();
  } catch {
    // A proxy 502 or an nginx error page is HTML, not our envelope.
  }

  const parsed = apiErrorBodySchema.safeParse(body);
  if (parsed.success) {
    return new ApiError({
      code: parsed.data.code,
      message: parsed.data.message,
      status: parsed.data.status,
      traceId: parsed.data.traceId,
      fieldErrors: parsed.data.fieldErrors,
      details: parsed.data.details,
      retryAfterSeconds:
        retryAfterSeconds !== undefined && Number.isFinite(retryAfterSeconds)
          ? retryAfterSeconds
          : undefined,
    });
  }

  // Not our envelope — something between us and Spring answered.
  return new ApiError({
    code: 'INTERNAL_ERROR',
    message: `Unexpected response from the server (HTTP ${String(response.status)}).`,
    status: response.status,
    retryAfterSeconds:
      retryAfterSeconds !== undefined && Number.isFinite(retryAfterSeconds)
        ? retryAfterSeconds
        : undefined,
  });
}

/**
 * `TSchema extends z.ZodTypeAny` plus the two overloads below give call sites a
 * precise return type: pass a schema and you get its inferred type back; omit
 * it (204 No Content) and you get `void`. No `any`, no casting at the call site.
 */
export async function request<TSchema extends z.ZodTypeAny>(
  path: string,
  options: RequestOptions<TSchema> & { schema: TSchema },
): Promise<z.infer<TSchema>>;
export async function request(
  path: string,
  options?: RequestOptions<z.ZodTypeAny>,
): Promise<void>;
export async function request<TSchema extends z.ZodTypeAny>(
  path: string,
  options: RequestOptions<TSchema> = {},
): Promise<unknown> {
  const { method = 'GET', body, headers = {}, schema, auth = true, signal } = options;

  /**
   * The actual fetch, factored out so the 401 path can run it a second time.
   * The token is read *inside* this function, not captured outside it — on the
   * retry we need the NEW token, and reading it late is what guarantees that.
   */
  const send = async (): Promise<Response> => {
    const requestHeaders: Record<string, string> = { Accept: 'application/json', ...headers };
    if (body !== undefined) requestHeaders['Content-Type'] = 'application/json';

    if (auth) {
      const token = getAccessToken();
      if (token !== null) requestHeaders['Authorization'] = `Bearer ${token}`;
    }

    const init: RequestInit = { method, headers: requestHeaders };
    if (body !== undefined) init.body = JSON.stringify(body);
    if (signal !== undefined) init.signal = signal;

    return fetch(`${API_ORIGIN}${API_PREFIX}${path}`, init);
  };

  let response: Response;
  try {
    response = await send();
  } catch {
    throw networkError();
  }

  /**
   * Exactly ONE refresh attempt, then exactly one retry. Not a loop: if the
   * retry also 401s, the session is genuinely dead and looping would just
   * hammer the auth endpoint into its 10/minute rate limit.
   *
   * `TOKEN_REUSE_DETECTED` is excluded on purpose — the backend has already
   * revoked the whole chain, so refreshing cannot possibly succeed.
   */
  if (response.status === 401 && auth) {
    const refreshed = await refreshOnce();
    if (refreshed) {
      try {
        response = await send();
      } catch {
        throw networkError();
      }
    } else {
      clearTokens();
      onSessionExpired?.();
    }
  }

  if (!response.ok) {
    throw await parseErrorResponse(response);
  }

  // 204 No Content (logout, release hold) has no body to parse.
  if (schema === undefined || response.status === 204) return undefined;

  let json: unknown;
  try {
    json = await response.json();
  } catch {
    throw new ApiError({
      code: 'MALFORMED_REQUEST',
      message: 'The server sent a response we could not read.',
      status: response.status,
    });
  }

  const parsed = schema.safeParse(json);
  if (!parsed.success) {
    // A contract violation, not a user error. Log the detail for the developer
    // and show the user something non-alarming.
    console.error('[SeatLock] response failed validation', path, parsed.error.issues);
    throw new ApiError({
      code: 'INTERNAL_ERROR',
      message: 'The server sent data in an unexpected format.',
      status: response.status,
    });
  }
  return parsed.data;
}

/** Builds a query string, skipping empty/undefined values. */
export function buildQuery(params: Record<string, string | number | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined) continue;
    const asString = String(value);
    if (asString.trim() === '') continue;
    search.set(key, asString);
  }
  const queryString = search.toString();
  return queryString === '' ? '' : `?${queryString}`;
}
