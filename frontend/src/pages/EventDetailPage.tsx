import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { getEvent } from '@/api/endpoints';
import { BackdropArt, PosterArt } from '@/components/PosterArt';
import { ErrorState, SkeletonBlock } from '@/components/states';
import {
  formatDate,
  formatDateTime,
  formatDuration,
  formatMoney,
  formatTime,
  titleCase,
} from '@/lib/format';

/**
 * Event detail.
 *
 * Structure, top to bottom: a wide procedural backdrop band with the poster
 * overlapping its lower edge, a title block, a dense metadata row, the
 * description, the tier/price strip, and the showtime button that leads into
 * the seat map. That is the order a ticketing site puts things in — the user is
 * three clicks from a purchase and everything above the fold should be helping
 * them decide, not explaining what the site is.
 */
export function EventDetailPage(): JSX.Element {
  const params = useParams<{ id: string }>();
  const eventId = Number(params.id);

  const eventQuery = useQuery({
    queryKey: ['event', eventId],
    queryFn: () => getEvent(eventId),
    // A NaN id (someone hand-edited the URL) must not become `GET /events/NaN`.
    enabled: Number.isFinite(eventId),
  });

  if (!Number.isFinite(eventId)) {
    return (
      <main className="mx-auto max-w-[1240px] px-4 py-10">
        <ErrorState error={new Error('bad id')} />
      </main>
    );
  }

  if (eventQuery.isPending) return <EventDetailSkeleton />;

  if (eventQuery.isError) {
    return (
      <main className="mx-auto max-w-[1240px] px-4 py-10">
        <ErrorState
          error={eventQuery.error}
          onRetry={() => {
            void eventQuery.refetch();
          }}
        />
      </main>
    );
  }

  const event = eventQuery.data;
  const duration = formatDuration(event.durationMinutes);
  const salesClosed = new Date(event.salesCloseAt).getTime() <= Date.now();
  const soldOut = event.availableSeats === 0;

  const metadata: Array<{ label: string; value: string }> = [
    { label: 'Category', value: titleCase(event.category) },
    { label: 'Language', value: event.language },
    ...(duration !== null ? [{ label: 'Runtime', value: duration }] : []),
    ...(event.certification != null ? [{ label: 'Rating', value: event.certification }] : []),
    { label: 'Venue', value: event.venue.name },
    { label: 'City', value: event.venue.city },
  ];

  return (
    <main className="pb-16">
      {/* The band. `absolute inset-0` inside a fixed-height relative box means
          the SVG crops (via preserveAspectRatio="slice") rather than squashing
          at any viewport width. */}
      <div className="relative h-[260px] w-full overflow-hidden sm:h-[320px]">
        <BackdropArt title={event.title} className="absolute inset-0 h-full w-full" />
      </div>

      <div className="mx-auto max-w-[1240px] px-4">
        {/* −96px pulls the poster up over the band's lower edge, the standard
            ticketing-site overlap. */}
        <div className="-mt-24 flex flex-col gap-6 sm:flex-row sm:items-end">
          <div className="w-[176px] shrink-0 overflow-hidden rounded-lg border border-ink-700">
            <PosterArt title={event.title} showTitle={false} className="block aspect-[2/3] w-full" />
          </div>

          <div className="min-w-0 flex-1 pb-1">
            {event.subtitle != null && event.subtitle !== '' ? (
              <p className="label-micro mb-1">{event.subtitle}</p>
            ) : null}
            <h1 className="display text-[44px] text-fog-50 sm:text-[56px]">{event.title}</h1>

            <div className="mt-2 flex flex-wrap items-center gap-2">
              {event.certification != null ? (
                <span className="pill border border-ink-700 bg-ink-850 text-fog-200">
                  {event.certification}
                </span>
              ) : null}
              <span className="pill border border-ink-700 bg-ink-850 text-fog-200">
                {event.language}
              </span>
              {soldOut ? (
                <span className="pill bg-ink-800 text-fog-400">Sold out</span>
              ) : (
                <span className="pill bg-mint-500 text-ink-950">
                  {String(event.availableSeats)} seats left
                </span>
              )}
            </div>
          </div>
        </div>

        {/* Metadata as a definition-list-style row of label/value pairs. Small
            uppercase labels over slightly larger values is the densest legible
            way to present six facts, and it is why this reads as a product
            rather than a blog post. */}
        <dl className="mt-7 grid grid-cols-2 gap-x-6 gap-y-4 border-y border-ink-700 py-4 sm:grid-cols-3 lg:grid-cols-6">
          {metadata.map((item) => (
            <div key={item.label}>
              <dt className="label-micro">{item.label}</dt>
              <dd className="mt-0.5 truncate text-sm text-fog-50">{item.value}</dd>
            </div>
          ))}
        </dl>

        <div className="mt-8 grid gap-8 lg:grid-cols-[minmax(0,1fr)_320px]">
          <section>
            <h2 className="label-micro">Synopsis</h2>
            {/* Rendered as a text child, so React escapes it. No
                dangerouslySetInnerHTML anywhere in this app, which is precisely
                why it needs no HTML sanitiser. */}
            <p className="mt-2 max-w-prose text-base leading-6 text-fog-200">
              {event.description != null && event.description !== ''
                ? event.description
                : 'No synopsis has been published for this event yet.'}
            </p>

            {event.tiers.length > 0 ? (
              <>
                <h2 className="label-micro mt-8">Seating tiers</h2>
                <ul className="mt-2 divide-y divide-ink-700 border-y border-ink-700">
                  {event.tiers.map((tier) => (
                    <li key={tier.id} className="flex items-center gap-3 py-2.5">
                      {/* A 3px colour bar rather than a dot: it matches the
                          seat-outline colour on the map, so a user can connect
                          "Prime is green" before they ever see the map. */}
                      <span
                        aria-hidden="true"
                        className="h-6 w-[3px] rounded-full"
                        style={{ backgroundColor: tier.colour ?? 'var(--fog-400)' }}
                      />
                      <span className="flex-1 text-sm font-semibold text-fog-50">
                        {titleCase(tier.name)}
                      </span>
                      <span className="text-sm font-semibold text-flare-500">
                        {formatMoney(tier.priceMinor)}
                      </span>
                    </li>
                  ))}
                </ul>
              </>
            ) : null}
          </section>

          {/* The booking panel. Sticky on desktop so the call to action stays
              in view while the user reads the synopsis. */}
          <aside className="lg:sticky lg:top-20 lg:self-start">
            <div className="surface p-4">
              <p className="label-micro">Showtime</p>
              <p className="mt-1 display text-[28px] text-fog-50">{formatTime(event.startsAt)}</p>
              <p className="mt-0.5 text-sm text-fog-400">{formatDate(event.startsAt)}</p>

              <div className="mt-4 flex items-baseline justify-between border-t border-ink-700 pt-3">
                <span className="label-micro">Tickets from</span>
                <span className="text-lg font-bold text-flare-500">
                  {formatMoney(event.minPriceMinor)}
                </span>
              </div>

              {salesClosed || soldOut ? (
                <>
                  <button type="button" className="btn-primary mt-4 h-10 w-full" disabled>
                    {soldOut ? 'Sold out' : 'Sales closed'}
                  </button>
                  <p className="mt-1.5 text-2xs text-fog-400">
                    {soldOut
                      ? 'Every seat for this show has been booked.'
                      : `Booking closed at ${formatDateTime(event.salesCloseAt)}.`}
                  </p>
                </>
              ) : (
                <>
                  <Link
                    to={`/events/${String(event.id)}/seats`}
                    className="btn-primary mt-4 h-10 w-full"
                  >
                    Select seats
                  </Link>
                  <p className="mt-1.5 text-2xs text-fog-400">
                    Booking closes {formatDateTime(event.salesCloseAt)}.
                  </p>
                </>
              )}
            </div>
          </aside>
        </div>
      </div>
    </main>
  );
}

/** Mirrors the real layout's shape, so nothing jumps when the data lands. */
function EventDetailSkeleton(): JSX.Element {
  return (
    <main className="pb-16">
      <SkeletonBlock className="h-[260px] w-full sm:h-[320px]" />
      <div className="mx-auto max-w-[1240px] px-4">
        <div className="-mt-24 flex flex-col gap-6 sm:flex-row sm:items-end">
          <SkeletonBlock className="aspect-[2/3] w-[176px] shrink-0 rounded-lg" />
          <div className="flex-1 pb-2">
            <SkeletonBlock className="h-10 w-2/3 rounded" />
            <SkeletonBlock className="mt-3 h-5 w-40 rounded-full" />
          </div>
        </div>
        <div className="mt-7 grid grid-cols-3 gap-6 border-y border-ink-700 py-4 lg:grid-cols-6">
          {Array.from({ length: 6 }, (_, index) => (
            <div key={index}>
              <SkeletonBlock className="h-2.5 w-14 rounded" />
              <SkeletonBlock className="mt-2 h-3.5 w-20 rounded" />
            </div>
          ))}
        </div>
      </div>
    </main>
  );
}
