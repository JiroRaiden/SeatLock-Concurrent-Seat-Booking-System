/**
 * Formatting helpers. Every one of these exists because the alternative was the
 * same three lines repeated on six screens with one of them subtly different.
 */

/**
 * Money.
 *
 * The API sends integers in PAISE and every such field ends in `Minor`, which
 * is the contract's way of making the unit impossible to mistake. Storing money
 * as an integer of the smallest unit is standard practice: `0.1 + 0.2 !== 0.3`
 * in IEEE-754 floats, so representing ₹650.00 as `650.0` invites rounding drift
 * the moment you sum a basket. We divide by 100 exactly once, here, at the
 * moment of display.
 *
 * `maximumFractionDigits: 0` is right for this product: Indian ticket prices
 * are whole rupees, and "₹650" is what BookMyShow shows. The `en-IN` locale
 * also gives the lakh/crore digit grouping (₹1,20,000, not ₹120,000), which is
 * the detail that makes it read as a genuinely Indian product.
 *
 * The formatter is constructed once at module scope rather than per call —
 * `Intl.NumberFormat` construction is comparatively expensive and the seat map
 * formats a price for every one of ~200 seats.
 */
const inrFormatter = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  maximumFractionDigits: 0,
});

export function formatMoney(minorUnits: number): string {
  return inrFormatter.format(minorUnits / 100);
}

/** For aria-labels and any place the ₹ glyph would be read out oddly. */
export function formatMoneySpoken(minorUnits: number): string {
  return `${String(Math.round(minorUnits / 100))} rupees`;
}

/* ------------------------------------------------------------------ time -- */

const dateTimeFormatter = new Intl.DateTimeFormat('en-IN', {
  weekday: 'short',
  day: '2-digit',
  month: 'short',
  hour: 'numeric',
  minute: '2-digit',
  hour12: true,
});

const dateFormatter = new Intl.DateTimeFormat('en-IN', {
  day: '2-digit',
  month: 'short',
  year: 'numeric',
});

const timeFormatter = new Intl.DateTimeFormat('en-IN', {
  hour: 'numeric',
  minute: '2-digit',
  hour12: true,
});

/**
 * All API timestamps are ISO-8601 with an offset, so `new Date(...)` parses
 * them unambiguously and `Intl` renders them in the viewer's own timezone —
 * which is what a user wants for a showtime.
 */
export function formatDateTime(iso: string): string {
  return dateTimeFormatter.format(new Date(iso));
}
export function formatDate(iso: string): string {
  return dateFormatter.format(new Date(iso));
}
export function formatTime(iso: string): string {
  return timeFormatter.format(new Date(iso));
}

/** "148" → "2h 28m". Returns null so callers can omit the field entirely. */
export function formatDuration(minutes: number | null | undefined): string | null {
  if (minutes === null || minutes === undefined || minutes <= 0) return null;
  const hours = Math.floor(minutes / 60);
  const remainder = minutes % 60;
  if (hours === 0) return `${String(remainder)}m`;
  if (remainder === 0) return `${String(hours)}h`;
  return `${String(hours)}h ${String(remainder)}m`;
}

/**
 * Seconds → "M:SS", zero-padded so the string width never changes. Combined
 * with `font-feature-settings: 'tnum'` in theme.css, this is what stops the
 * checkout countdown from visibly shifting as it ticks.
 */
export function formatCountdown(totalSeconds: number): string {
  const safeSeconds = Math.max(0, Math.floor(totalSeconds));
  const minutes = Math.floor(safeSeconds / 60);
  const seconds = safeSeconds % 60;
  return `${String(minutes)}:${seconds.toString().padStart(2, '0')}`;
}

/** "MOVIE" → "Movie". Used for category chips and tier names. */
export function titleCase(value: string): string {
  return value
    .toLowerCase()
    .split(/[\s_]+/)
    .map((word) => (word.length === 0 ? word : word[0]!.toUpperCase() + word.slice(1)))
    .join(' ');
}
