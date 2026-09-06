import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { listBookings } from '@/api/endpoints';
import type { BookingStatus, BookingSummary } from '@/api/schemas';
import { EmptyState, ErrorState, SkeletonBlock } from '@/components/states';
import { formatDateTime, formatMoney, titleCase } from '@/lib/format';

/**
 * My bookings.
 *
 * Dense table-like rows, not cards. A user with fifteen bookings wants to scan
 * a list; a grid of cards would put four on a screen and hide the rest. Each
 * row expands in place to show its seats, so checking "which seats did I get?"
 * never costs a page load.
 */
export function BookingsPage(): JSX.Element {
  const bookingsQuery = useQuery({
    queryKey: ['bookings'],
    queryFn: () => listBookings({ size: 50 }),
  });

  return (
    <main className="mx-auto max-w-[1000px] px-4 pb-16 pt-6">
      <h1 className="display text-[32px] text-fog-50">My bookings</h1>
      <p className="mt-1 text-sm text-fog-400">Newest first.</p>

      <div className="mt-6">
        {bookingsQuery.isPending ? (
          <div className="flex flex-col gap-2">
            {Array.from({ length: 4 }, (_, index) => (
              <SkeletonBlock key={index} className="h-[68px] w-full rounded-lg" />
            ))}
          </div>
        ) : bookingsQuery.isError ? (
          <ErrorState
            error={bookingsQuery.error}
            onRetry={() => {
              void bookingsQuery.refetch();
            }}
          />
        ) : bookingsQuery.data.content.length === 0 ? (
          <EmptyState
            title="No bookings yet"
            hint="Once you book a show it will appear here with its seats and reference."
            action={
              <Link to="/" className="btn-primary mt-2">
                Browse events
              </Link>
            }
          />
        ) : (
          <ul className="flex flex-col gap-2">
            {bookingsQuery.data.content.map((booking) => (
              <BookingRow key={booking.id} booking={booking} />
            ))}
          </ul>
        )}
      </div>
    </main>
  );
}

function BookingRow({ booking }: { booking: BookingSummary }): JSX.Element {
  const [expanded, setExpanded] = useState(false);
  const seatCount = booking.seats.length;

  return (
    <li className="surface overflow-hidden">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2 p-3">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <StatusPill status={booking.status} />
            <span className="text-2xs uppercase tracking-label text-fog-400">
              {booking.reference}
            </span>
          </div>
          <p className="mt-1 truncate text-sm font-semibold text-fog-50">
            {booking.event.title}
          </p>
          <p className="mt-0.5 truncate text-xs text-fog-400">
            {formatDateTime(booking.event.startsAt)} · {booking.event.venue.name}
          </p>
        </div>

        <div className="text-right">
          <p className="label-micro">
            {seatCount > 0 ? `${String(seatCount)} seat${seatCount === 1 ? '' : 's'}` : 'Seats'}
          </p>
          <p className="text-sm font-semibold text-flare-500">
            {formatMoney(booking.totalMinor)}
          </p>
        </div>

        <div className="flex shrink-0 items-center gap-2">
          {seatCount > 0 ? (
            <button
              type="button"
              onClick={() => {
                setExpanded((open) => !open);
              }}
              aria-expanded={expanded}
              // Names the panel it controls so a screen reader can jump to it.
              aria-controls={`booking-seats-${booking.id}`}
              className="btn-ghost h-8 px-2.5"
            >
              {expanded ? 'Hide seats' : 'Show seats'}
            </button>
          ) : null}
          <Link to={`/bookings/${booking.id}`} className="btn-ghost h-8 px-2.5">
            Ticket
          </Link>
        </div>
      </div>

      {expanded ? (
        <div
          id={`booking-seats-${booking.id}`}
          className="animate-fade-up border-t border-ink-700 bg-ink-900 px-3 py-2.5"
        >
          <ul className="flex flex-wrap gap-1.5">
            {booking.seats.map((seat) => (
              <li
                key={seat.id}
                className="pill border border-ink-700 bg-ink-850 text-fog-200"
                title={`${titleCase(seat.section)} · ${formatMoney(seat.priceMinor)}`}
              >
                {seat.label}
                <span className="text-fog-400">{formatMoney(seat.priceMinor)}</span>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </li>
  );
}

/**
 * Status colours are semantic and reused from the seat map's vocabulary:
 * mint = good/confirmed, amber = pending/waiting, and a flat grey for anything
 * that is over. Consistency between the two screens is what makes the palette
 * feel designed rather than decorated.
 */
export function StatusPill({ status }: { status: BookingStatus }): JSX.Element {
  const styles: Record<BookingStatus, string> = {
    CONFIRMED: 'bg-mint-500 text-ink-950',
    PENDING: 'bg-amber-500 text-ink-950',
    CANCELLED: 'border border-ink-700 bg-ink-800 text-fog-400',
    EXPIRED: 'border border-ink-700 bg-ink-800 text-fog-400',
  };
  return <span className={`pill ${styles[status]}`}>{titleCase(status)}</span>;
}
