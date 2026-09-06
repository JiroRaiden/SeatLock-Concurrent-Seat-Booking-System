import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { cancelBooking, getBooking } from '@/api/endpoints';
import { PosterArt } from '@/components/PosterArt';
import { ErrorState, SkeletonBlock } from '@/components/states';
import { StatusPill } from '@/pages/BookingsPage';
import { formatDate, formatDateTime, formatMoney, formatTime, titleCase } from '@/lib/format';

/**
 * The ticket.
 *
 * Shaped like a physical cinema stub: a main body, a perforated tear line, and
 * a smaller counterfoil carrying the reference. The perforation is drawn with
 * `radial-gradient` notches rather than an image or a border trick — see the
 * comment on the divider below. It is the one piece of skeuomorphism in the
 * app, and it earns its place because a ticket that looks like a ticket is
 * instantly recognisable in a screenshot or a phone gallery.
 */
export function BookingDetailPage(): JSX.Element {
  const params = useParams<{ id: string }>();
  const bookingId = params.id ?? '';
  const queryClient = useQueryClient();

  const bookingQuery = useQuery({
    queryKey: ['booking', bookingId],
    queryFn: () => getBooking(bookingId),
    enabled: bookingId !== '',
  });

  const cancelMutation = useMutation({
    mutationFn: () => cancelBooking(bookingId),
    onSuccess: (updated) => {
      queryClient.setQueryData(['booking', bookingId], updated);
      void queryClient.invalidateQueries({ queryKey: ['bookings'] });
    },
  });

  if (bookingQuery.isPending) {
    return (
      <main className="mx-auto max-w-[560px] px-4 pt-8">
        <SkeletonBlock className="h-[420px] w-full rounded-lg" />
      </main>
    );
  }

  if (bookingQuery.isError) {
    return (
      <main className="mx-auto max-w-[560px] px-4 py-10">
        {/* A 404 here means "does not exist, OR is not yours" — API.md returns
            404 rather than 403 on purpose, so that an attacker cannot use the
            response code to discover which booking ids are real. The message
            the server sends is already worded for that, so we just show it. */}
        <ErrorState
          error={bookingQuery.error}
          onRetry={() => {
            void bookingQuery.refetch();
          }}
        />
        <Link to="/bookings" className="btn-ghost mt-4">
          Back to my bookings
        </Link>
      </main>
    );
  }

  const booking = bookingQuery.data;
  const cancellable = booking.status === 'CONFIRMED';

  return (
    <main className="mx-auto max-w-[560px] px-4 pb-16 pt-6">
      <Link to="/bookings" className="label-micro transition-colors hover:text-flare-400">
        &larr; All bookings
      </Link>

      <article className="mt-3 overflow-hidden rounded-lg border border-ink-700 bg-ink-850">
        {/* --- stub body --- */}
        <div className="flex gap-4 p-5">
          <div className="w-[96px] shrink-0 overflow-hidden rounded border border-ink-700">
            <PosterArt
              title={booking.event.title}
              showTitle={false}
              className="block aspect-[2/3] w-full"
            />
          </div>

          <div className="min-w-0 flex-1">
            <StatusPill status={booking.status} />
            <h1 className="display mt-1.5 text-[30px] leading-[0.95] text-fog-50">
              {booking.event.title}
            </h1>
            <p className="mt-1.5 text-xs text-fog-400">
              {booking.event.venue.name}, {booking.event.venue.city}
            </p>
          </div>
        </div>

        <dl className="grid grid-cols-3 gap-3 border-t border-ink-700 px-5 py-3">
          <div>
            <dt className="label-micro">Date</dt>
            <dd className="mt-0.5 text-sm text-fog-50">{formatDate(booking.event.startsAt)}</dd>
          </div>
          <div>
            <dt className="label-micro">Time</dt>
            <dd className="mt-0.5 text-sm text-fog-50">{formatTime(booking.event.startsAt)}</dd>
          </div>
          <div>
            <dt className="label-micro">Seats</dt>
            <dd className="mt-0.5 text-sm text-fog-50">{String(booking.seats.length)}</dd>
          </div>
        </dl>

        <div className="border-t border-ink-700 px-5 py-3">
          <p className="label-micro">Your seats</p>
          <ul className="mt-2 flex flex-wrap gap-1.5">
            {booking.seats.map((seat) => (
              <li
                key={seat.id}
                className="rounded border border-ink-700 bg-ink-800 px-2 py-1 text-center"
              >
                <span className="block text-sm font-bold text-fog-50">{seat.label}</span>
                <span className="block text-2xs uppercase tracking-label text-fog-400">
                  {titleCase(seat.section)}
                </span>
              </li>
            ))}
          </ul>
        </div>

        {/*
          THE PERFORATION.

          One element, three layers of background:
            1. a repeating-linear-gradient dash — the cut line itself;
            2. a radial-gradient circle pinned to the left edge, coloured to the
               PAGE background, which punches a semicircular notch out of the
               card;
            3. the same on the right.

          Painting the notch in the page colour is the trick: the card is opaque,
          so a "hole" is really a circle of whatever is behind it. That means no
          image, no clip-path, and no extra DOM — and it resizes with the card.
        */}
        <div
          aria-hidden="true"
          className="relative h-6"
          style={{
            background: [
              'radial-gradient(circle at 0 50%, var(--ink-950) 11px, transparent 11px)',
              'radial-gradient(circle at 100% 50%, var(--ink-950) 11px, transparent 11px)',
              'repeating-linear-gradient(to right, var(--ink-700) 0 6px, transparent 6px 12px)',
            ].join(', '),
            backgroundRepeat: 'no-repeat, no-repeat, no-repeat',
            backgroundSize: '100% 100%, 100% 100%, calc(100% - 44px) 1px',
            backgroundPosition: 'left center, right center, center center',
          }}
        />

        {/* --- counterfoil --- */}
        <div className="flex items-end justify-between gap-4 px-5 pb-5 pt-1">
          <div className="min-w-0">
            <p className="label-micro">Booking reference</p>
            {/* Bebas Neue, large: this is the string a user reads aloud at a
                counter, so it gets the most legible treatment on the card. */}
            <p className="display mt-0.5 text-[34px] leading-none text-fog-50">
              {booking.reference}
            </p>
            <p className="mt-1.5 text-2xs text-fog-400">
              Booked {formatDateTime(booking.createdAt)}
            </p>
          </div>

          <div className="shrink-0 text-right">
            <p className="label-micro">Total paid</p>
            <p className="display text-[30px] leading-none text-flare-500">
              {formatMoney(booking.totalMinor)}
            </p>
          </div>
        </div>
      </article>

      {cancellable ? (
        <div className="mt-4">
          <button
            type="button"
            className="btn-ghost"
            disabled={cancelMutation.isPending}
            onClick={() => {
              cancelMutation.mutate();
            }}
          >
            {cancelMutation.isPending ? 'Cancelling…' : 'Cancel this booking'}
          </button>
          <p className="mt-1.5 text-2xs text-fog-400">
            Cancelling returns these seats to sale immediately.
          </p>
          {cancelMutation.isError ? (
            <div className="mt-3">
              <ErrorState error={cancelMutation.error} compact />
            </div>
          ) : null}
        </div>
      ) : null}
    </main>
  );
}
