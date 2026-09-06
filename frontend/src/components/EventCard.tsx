import { Link } from 'react-router-dom';
import { PosterArt } from '@/components/PosterArt';
import type { EventSummary } from '@/api/schemas';
import { formatDuration, formatMoney } from '@/lib/format';

/**
 * One poster card on a rail.
 *
 * The information hierarchy is copied from real Indian ticketing sites, in this
 * order: artwork, title, certification + language + runtime on one dense line,
 * then the "from ₹280" price in the accent colour. Price is the thing a user is
 * actually scanning for, so it is the only coloured text on the card.
 */
export function EventCard({ event }: { event: EventSummary }): JSX.Element {
  const duration = formatDuration(event.durationMinutes);

  // "Fast filling" / "Sold out" style urgency, computed rather than invented:
  // it comes straight from the availability numbers the list endpoint returns.
  const availabilityRatio =
    event.totalSeats > 0 ? event.availableSeats / event.totalSeats : 0;
  const soldOut = event.availableSeats === 0;
  const fillingFast = !soldOut && availabilityRatio < 0.25;

  return (
    <Link
      to={`/events/${String(event.id)}`}
      // `snap-start` makes the rail settle with a card flush to the left edge
      // instead of stopping mid-card.
      className="group w-[168px] shrink-0 snap-start focus-visible:outline-none"
    >
      <div className="relative overflow-hidden rounded-lg border border-ink-700 transition-colors group-hover:border-ink-600 group-focus-visible:border-flare-400">
        <PosterArt
          title={event.title}
          // The card prints the title underneath, so the artwork carries only
          // the geometry — otherwise the title appears twice, 8px apart.
          showTitle={false}
          className="block aspect-[2/3] w-full"
        />

        {soldOut ? (
          <span className="pill absolute left-2 top-2 bg-[var(--scrim-strong)] text-fog-200">
            Sold out
          </span>
        ) : fillingFast ? (
          <span className="pill absolute left-2 top-2 bg-flare-500 text-white">Filling fast</span>
        ) : null}

        {event.certification !== null && event.certification !== undefined ? (
          <span className="pill absolute bottom-2 right-2 border border-ink-700 bg-[var(--scrim-soft)] text-fog-200">
            {event.certification}
          </span>
        ) : null}
      </div>

      {/* React escapes interpolated strings by default, so a title containing
          `<script>` renders as literal text. That is why there is no sanitiser
          anywhere in this project — and why `dangerouslySetInnerHTML` is never
          used with server data. */}
      <p className="mt-2 truncate text-sm font-semibold text-fog-50 transition-colors group-hover:text-flare-400">
        {event.title}
      </p>

      <p className="mt-0.5 truncate text-xs text-fog-400">
        {[event.language, duration].filter((part) => part !== null && part !== '').join(' · ')}
      </p>

      <p className="mt-1 text-xs font-semibold text-flare-500">
        from {formatMoney(event.minPriceMinor)}
      </p>
    </Link>
  );
}
