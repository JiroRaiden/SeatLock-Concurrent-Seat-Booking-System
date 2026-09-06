import { buildQuery, request } from '@/lib/apiClient';
import {
  authResponseSchema,
  bookingDetailSchema,
  bookingSummarySchema,
  eventDetailSchema,
  eventSummarySchema,
  holdExtendSchema,
  holdResponseSchema,
  pageSchema,
  seatMapSchema,
  userSummarySchema,
  type AuthResponse,
  type BookingDetail,
  type BookingSummary,
  type EventDetail,
  type EventSummary,
  type HoldExtend,
  type HoldResponse,
  type Page,
  type SeatMap,
  type UserSummary,
} from '@/api/schemas';

/**
 * One function per endpoint in API.md, and nothing else. No endpoint is
 * invented here and no field is renamed on the way through — if a name looks
 * awkward in the UI, the UI adapts, because the contract is fixed.
 *
 * Keeping these as plain async functions (rather than hooks) means the same
 * call can be used by a TanStack Query `queryFn`, a `mutationFn`, or a router
 * loader without duplication.
 */

/* ------------------------------------------------------------------ auth -- */

export function register(input: {
  email: string;
  password: string;
  displayName: string;
}): Promise<AuthResponse> {
  return request('/auth/register', {
    method: 'POST',
    body: input,
    schema: authResponseSchema,
    auth: false,
  });
}

export function login(input: { email: string; password: string }): Promise<AuthResponse> {
  return request('/auth/login', {
    method: 'POST',
    body: input,
    schema: authResponseSchema,
    auth: false,
  });
}

/**
 * Logout is best-effort from the client's point of view: it tells the server to
 * revoke the refresh chain, but we clear local tokens regardless of the result
 * (see AuthContext). A failed logout must never leave the user looking
 * logged in.
 */
export function logout(refreshToken: string): Promise<void> {
  return request('/auth/logout', { method: 'POST', body: { refreshToken } });
}

export function getMe(): Promise<UserSummary> {
  return request('/auth/me', { schema: userSummarySchema });
}

/* ---------------------------------------------------------------- events -- */

export function listEvents(params: {
  category?: string | undefined;
  city?: string | undefined;
  q?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}): Promise<Page<EventSummary>> {
  return request(`/events${buildQuery(params)}`, {
    schema: pageSchema(eventSummarySchema),
    auth: false,
  });
}

export function getEvent(id: number): Promise<EventDetail> {
  return request(`/events/${String(id)}`, { schema: eventDetailSchema, auth: false });
}

export function getSeatMap(id: number): Promise<SeatMap> {
  return request(`/events/${String(id)}/seatmap`, { schema: seatMapSchema, auth: false });
}

/* ----------------------------------------------------------------- holds -- */

/**
 * The only write that happens on the seat map, and it happens once, when the
 * user presses Proceed. Holds are all-or-nothing: a 409 means nothing was
 * reserved, so there is no partial state to clean up on the client.
 */
export function createHold(eventId: number, seatIds: number[]): Promise<HoldResponse> {
  return request(`/events/${String(eventId)}/holds`, {
    method: 'POST',
    body: { seatIds },
    schema: holdResponseSchema,
  });
}

export function extendHold(holdId: string): Promise<HoldExtend> {
  return request(`/holds/${encodeURIComponent(holdId)}/extend`, {
    method: 'POST',
    schema: holdExtendSchema,
  });
}

export function releaseHold(holdId: string): Promise<void> {
  return request(`/holds/${encodeURIComponent(holdId)}`, { method: 'DELETE' });
}

/* -------------------------------------------------------------- bookings -- */

/**
 * `Idempotency-Key` is REQUIRED by the contract. It is generated once per
 * checkout attempt and reused across retries, which is the entire point: if the
 * network drops after the server charged but before we saw the response, the
 * retry replays the original response instead of creating a second booking.
 *
 * The key is therefore passed in by the caller rather than generated here — a
 * key generated inside this function would be new on every retry and would
 * defeat the mechanism.
 */
export function confirmBooking(
  holdId: string,
  input: { paymentMethod: string; paymentToken: string },
  idempotencyKey: string,
): Promise<BookingDetail> {
  return request(`/bookings/${encodeURIComponent(holdId)}/confirm`, {
    method: 'POST',
    body: input,
    headers: { 'Idempotency-Key': idempotencyKey },
    schema: bookingDetailSchema,
  });
}

export function listBookings(params: {
  page?: number | undefined;
  size?: number | undefined;
}): Promise<Page<BookingSummary>> {
  return request(`/bookings${buildQuery(params)}`, {
    schema: pageSchema(bookingSummarySchema),
  });
}

export function getBooking(holdId: string): Promise<BookingDetail> {
  return request(`/bookings/${encodeURIComponent(holdId)}`, { schema: bookingDetailSchema });
}

export function cancelBooking(holdId: string): Promise<BookingDetail> {
  return request(`/bookings/${encodeURIComponent(holdId)}/cancel`, {
    method: 'POST',
    schema: bookingDetailSchema,
  });
}
