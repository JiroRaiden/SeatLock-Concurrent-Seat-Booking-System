import { z } from 'zod';

/**
 * Runtime shapes for every API response we consume.
 *
 * Why parse at all, when TypeScript already has interfaces?
 * Because `as EventDetail` is a lie the compiler cannot check. The type says
 * what we *hope* arrives; zod checks what actually did. The backend is being
 * written in parallel by someone else, so the first time a field gets renamed
 * or nulled we want a loud, specific error at the boundary — "expected number,
 * received null at rows[3].seats[7].priceMinor" — instead of `NaN` silently
 * reaching a price total three screens later.
 *
 * The parse happens in exactly one place (`apiClient.request`), so no component
 * ever touches unvalidated data.
 */

/* ---------------------------------------------------------------- errors -- */

/** The one error envelope from API.md. Every non-2xx response has this shape. */
export const apiErrorBodySchema = z.object({
  code: z.string(),
  message: z.string(),
  status: z.number(),
  timestamp: z.string().optional(),
  traceId: z.string().optional(),
  /** Present on VALIDATION_FAILED: field name → human-readable reason. */
  fieldErrors: z.record(z.string(), z.string()).optional(),
  /** Present on SEAT_UNAVAILABLE: `{ unavailableSeatIds: number[] }`. */
  details: z.record(z.string(), z.unknown()).optional(),
});
export type ApiErrorBody = z.infer<typeof apiErrorBodySchema>;

/* ------------------------------------------------------------------ auth -- */

export const userSummarySchema = z.object({
  id: z.number(),
  email: z.string(),
  displayName: z.string(),
  role: z.string(),
});
export type UserSummary = z.infer<typeof userSummarySchema>;

export const authResponseSchema = z.object({
  accessToken: z.string(),
  refreshToken: z.string(),
  tokenType: z.string(),
  expiresInSeconds: z.number(),
  user: userSummarySchema,
});
export type AuthResponse = z.infer<typeof authResponseSchema>;

/* ---------------------------------------------------------------- events -- */

export const venueSchema = z.object({
  id: z.number(),
  name: z.string(),
  city: z.string(),
});
export type Venue = z.infer<typeof venueSchema>;

/**
 * `.nullable()` on optional-ish fields is intentional and matches API.md:
 * `posterUrl` is documented as always `null` in the seeded data. Modelling it
 * as `string | null` rather than `string | undefined` means the PosterArt
 * fallback path is a normal branch, not an error case.
 */
export const eventSummarySchema = z.object({
  id: z.number(),
  title: z.string(),
  subtitle: z.string().nullable().optional(),
  category: z.string(),
  language: z.string(),
  certification: z.string().nullable().optional(),
  durationMinutes: z.number().nullable().optional(),
  posterUrl: z.string().nullable().optional(),
  startsAt: z.string(),
  salesCloseAt: z.string(),
  venue: venueSchema,
  minPriceMinor: z.number(),
  availableSeats: z.number(),
  totalSeats: z.number(),
});
export type EventSummary = z.infer<typeof eventSummarySchema>;

export const tierSchema = z.object({
  id: z.number(),
  name: z.string(),
  priceMinor: z.number(),
  colour: z.string().nullable().optional(),
});
export type Tier = z.infer<typeof tierSchema>;

export const eventDetailSchema = eventSummarySchema.extend({
  description: z.string().nullable().optional(),
  backdropUrl: z.string().nullable().optional(),
  tiers: z.array(tierSchema).default([]),
});
export type EventDetail = z.infer<typeof eventDetailSchema>;

/**
 * A generic page wrapper. Taking the item schema as a parameter keeps one
 * definition for the envelope instead of one per resource.
 */
export function pageSchema<T extends z.ZodTypeAny>(item: T) {
  return z.object({
    content: z.array(item),
    page: z.number(),
    size: z.number(),
    totalElements: z.number(),
    totalPages: z.number(),
    last: z.boolean(),
  });
}
export type Page<T> = {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  last: boolean;
};

/* --------------------------------------------------------------- seatmap -- */

/**
 * The four seat states. `HELD` only ever appears in the seatmap response — it
 * is computed from Redis at read time and is not a durable database state, so
 * a held seat may quietly become available again while the user is looking at
 * it. The UI reflects that with a pulse rather than a static "unavailable".
 */
export const seatStatusSchema = z.enum(['AVAILABLE', 'BOOKED', 'BLOCKED', 'HELD']);
export type SeatStatus = z.infer<typeof seatStatusSchema>;

export const seatSchema = z.object({
  id: z.number(),
  label: z.string(),
  seatNumber: z.number(),
  /** Already includes the centre-aisle gap, so it maps straight to gridColumn. */
  colIndex: z.number(),
  tierId: z.number(),
  priceMinor: z.number(),
  status: seatStatusSchema,
});
export type Seat = z.infer<typeof seatSchema>;

export const seatRowSchema = z.object({
  rowLabel: z.string(),
  rowIndex: z.number(),
  section: z.string(),
  seats: z.array(seatSchema),
});
export type SeatRow = z.infer<typeof seatRowSchema>;

export const seatMapSchema = z.object({
  eventId: z.number(),
  venueName: z.string(),
  salesCloseAt: z.string(),
  tiers: z.array(tierSchema),
  rows: z.array(seatRowSchema),
  summary: z.object({
    available: z.number(),
    booked: z.number(),
    blocked: z.number(),
    held: z.number(),
  }),
});
export type SeatMap = z.infer<typeof seatMapSchema>;

/* ----------------------------------------------------------------- holds -- */

export const holdSeatSchema = z.object({
  id: z.number(),
  label: z.string(),
  priceMinor: z.number(),
});

export const holdResponseSchema = z.object({
  /** Also the pending booking's publicId — one identifier, deliberately. */
  holdId: z.string(),
  eventId: z.number(),
  expiresAt: z.string(),
  ttlSeconds: z.number(),
  totalMinor: z.number(),
  seats: z.array(holdSeatSchema),
});
export type HoldResponse = z.infer<typeof holdResponseSchema>;

export const holdExtendSchema = z.object({
  expiresAt: z.string(),
  ttlSeconds: z.number(),
});
export type HoldExtend = z.infer<typeof holdExtendSchema>;

/* -------------------------------------------------------------- bookings -- */

export const bookingStatusSchema = z.enum([
  'PENDING',
  'CONFIRMED',
  'CANCELLED',
  'EXPIRED',
]);
export type BookingStatus = z.infer<typeof bookingStatusSchema>;

export const bookingSeatSchema = z.object({
  id: z.number(),
  label: z.string(),
  section: z.string(),
  priceMinor: z.number(),
});
export type BookingSeat = z.infer<typeof bookingSeatSchema>;

export const bookingEventSchema = z.object({
  id: z.number(),
  title: z.string(),
  startsAt: z.string(),
  venue: venueSchema,
});

export const bookingDetailSchema = z.object({
  id: z.string(),
  reference: z.string(),
  /** `.catch()` keeps an unknown future status from breaking the list page. */
  status: bookingStatusSchema.catch('PENDING'),
  totalMinor: z.number(),
  createdAt: z.string(),
  expiresAt: z.string().nullable().optional(),
  confirmedAt: z.string().nullable().optional(),
  event: bookingEventSchema,
  seats: z.array(bookingSeatSchema).default([]),
});
export type BookingDetail = z.infer<typeof bookingDetailSchema>;

/**
 * API.md says GET /bookings returns "paginated BookingSummary" but does not
 * print the BookingSummary shape. Rather than invent fields, we reuse the
 * BookingDetail schema with the seat list optional — a summary is a detail with
 * possibly less in it, and zod ignores extra keys by default, so this parses
 * correctly whichever of the two the server actually sends.
 */
export const bookingSummarySchema = bookingDetailSchema.extend({
  seats: z.array(bookingSeatSchema).default([]),
});
export type BookingSummary = z.infer<typeof bookingSummarySchema>;
