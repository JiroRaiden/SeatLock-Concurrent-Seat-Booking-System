import { useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { listEvents } from '@/api/endpoints';
import type { EventSummary } from '@/api/schemas';
import { EventCard } from '@/components/EventCard';
import { Rail } from '@/components/Rail';
import { EmptyState, ErrorState, RailSkeleton } from '@/components/states';

/**
 * Browse.
 *
 * Layout: a stack of horizontal rails, one per category, exactly like
 * BookMyShow's homepage. Each category is its own query rather than one big
 * fetch that we slice client-side, because (a) the API supports a `category`
 * filter so the server can do the work, and (b) three independent queries mean
 * the Concerts rail still renders if the Comedy one fails.
 */

/** The rails, in display order. `category` matches the API's enum values. */
const RAILS = [
  { category: 'MOVIE', title: 'Now showing', subtitle: 'In cinemas near you this week' },
  { category: 'CONCERT', title: 'Concerts', subtitle: 'Live music, arenas and clubs' },
  { category: 'COMEDY', title: 'Comedy', subtitle: 'Stand-up, improv and open mics' },
] as const;

export function BrowsePage(): JSX.Element {
  const [searchParams] = useSearchParams();
  const city = searchParams.get('city') ?? undefined;
  const query = searchParams.get('q') ?? undefined;

  /**
   * When the user searches, the category rails stop making sense — they want
   * one ranked list of matches, not "your search, but split into three rows".
   * So a search swaps the whole page over to a single result grid.
   */
  const searching = query !== undefined && query.trim() !== '';

  return (
    <main className="mx-auto max-w-[1240px] px-4 pb-16">
      {searching ? (
        <SearchResults query={query} city={city} />
      ) : (
        <>
          <MarqueeStrip />
          {RAILS.map((rail) => (
            <CategoryRail
              key={rail.category}
              category={rail.category}
              title={rail.title}
              subtitle={rail.subtitle}
              city={city}
            />
          ))}
        </>
      )}
    </main>
  );
}

/**
 * A thin editorial strip under the header instead of a hero.
 *
 * A large centred headline with two buttons is the visual signature of a
 * landing page, and this is not a landing page — the user came here to find a
 * showtime. A single dense line of live-looking metadata sets the tone in 40px
 * of vertical space instead of 500.
 */
function MarqueeStrip(): JSX.Element {
  return (
    <div className="mt-4 flex flex-wrap items-center gap-x-6 gap-y-2 border-b border-ink-700 pb-3">
      <span className="flex items-center gap-1.5">
        {/* A live dot: two stacked spans, one pulsing. Communicates "this data
            is current" more cheaply than any amount of copy. */}
        <span className="relative flex h-1.5 w-1.5">
          <span className="absolute inline-flex h-full w-full animate-hold-pulse rounded-full bg-flare-500" />
          <span className="relative inline-flex h-1.5 w-1.5 rounded-full bg-flare-500" />
        </span>
        <span className="label-micro text-flare-500">Live inventory</span>
      </span>
      <p className="text-xs text-fog-400">
        Seats are reserved the moment you press Proceed and held for 8 minutes.
      </p>
    </div>
  );
}

function CategoryRail({
  category,
  title,
  subtitle,
  city,
}: {
  category: string;
  title: string;
  subtitle: string;
  city: string | undefined;
}): JSX.Element {
  const eventsQuery = useQuery({
    // The key includes every input that changes the result. Getting this wrong
    // is the classic TanStack Query bug: omit `city` and switching city would
    // serve the previous city's cached rail forever.
    queryKey: ['events', { category, city }],
    queryFn: () => listEvents({ category, city, size: 20 }),
  });

  if (eventsQuery.isPending) {
    return (
      <section className="py-5">
        <div className="mb-3 h-6 w-40 animate-pulse rounded bg-ink-800" />
        <RailSkeleton />
      </section>
    );
  }

  if (eventsQuery.isError) {
    return (
      <section className="py-5">
        <h2 className="display mb-3 text-[22px] text-fog-50">{title}</h2>
        <ErrorState
          error={eventsQuery.error}
          compact
          onRetry={() => {
            void eventsQuery.refetch();
          }}
        />
      </section>
    );
  }

  // An empty category is not an error — it just does not get a rail. Rendering
  // "no concerts" three times on a quiet week is noise.
  if (eventsQuery.data.content.length === 0) return <></>;

  return (
    <Rail title={title} subtitle={subtitle}>
      {eventsQuery.data.content.map((event: EventSummary) => (
        <EventCard key={event.id} event={event} />
      ))}
    </Rail>
  );
}

function SearchResults({
  query,
  city,
}: {
  query: string;
  city: string | undefined;
}): JSX.Element {
  const resultsQuery = useQuery({
    queryKey: ['events', 'search', { query, city }],
    queryFn: () => listEvents({ q: query, city, size: 50 }),
  });

  return (
    <section className="py-6">
      <h1 className="display text-[26px] text-fog-50">
        Results for &ldquo;{query}&rdquo;
      </h1>

      {resultsQuery.isPending ? (
        <div className="mt-4">
          <RailSkeleton count={8} />
        </div>
      ) : resultsQuery.isError ? (
        <div className="mt-4">
          <ErrorState
            error={resultsQuery.error}
            onRetry={() => {
              void resultsQuery.refetch();
            }}
          />
        </div>
      ) : resultsQuery.data.content.length === 0 ? (
        <div className="mt-4">
          <EmptyState
            title="Nothing matched"
            hint="Try a shorter search, or check the city selector in the header."
          />
        </div>
      ) : (
        <>
          <p className="mt-1 text-xs text-fog-400">
            {String(resultsQuery.data.totalElements)} event
            {resultsQuery.data.totalElements === 1 ? '' : 's'}
          </p>
          {/* Search results ARE a grid — here breadth is the point, and a rail
              would hide most of the matches off-screen. */}
          <div className="mt-4 grid grid-cols-[repeat(auto-fill,minmax(168px,1fr))] gap-x-3 gap-y-6">
            {resultsQuery.data.content.map((event) => (
              <EventCard key={event.id} event={event} />
            ))}
          </div>
        </>
      )}
    </section>
  );
}
