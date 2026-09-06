import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { createHold, getSeatMap } from '@/api/endpoints';
import type { Seat, SeatMap, SeatRow, Tier } from '@/api/schemas';
import { useAuth } from '@/auth/AuthContext';
import { ErrorState, SkeletonBlock } from '@/components/states';
import { Legend } from '@/features/seatmap/Legend';
import { ScreenArc } from '@/features/seatmap/ScreenArc';
import { SeatButton, type SeatVisualState } from '@/features/seatmap/Seat';
import { SelectionBar } from '@/features/seatmap/SelectionBar';
import {
  computeColumnCount,
  computeRowGeometry,
  seatOffsetY,
} from '@/features/seatmap/geometry';
import { isApiError } from '@/lib/apiClient';
import { formatMoney, titleCase } from '@/lib/format';

/**
 * THE SEAT MAP.
 *
 * ── The one architectural decision that matters here ──────────────────────
 *
 * CLICKING A SEAT MAKES NO NETWORK CALL. Selection is ordinary React state (a
 * `Set` of seat ids) and stays entirely on the client until the user presses
 * Proceed, at which point a single `POST /events/{id}/holds` reserves the whole
 * set atomically.
 *
 * This is not a shortcut, and API.md calls it out explicitly. Taking a lock on
 * every seat tap would mean a popular release generates thousands of
 * reservations per second, almost all of which are abandoned seconds later when
 * the user changes their mind — every one of those needing a distributed lock,
 * a TTL, and a release. Deferring the write to the moment of commitment means
 * one write per genuine intent instead of one per twitch of the mouse.
 *
 * The cost of deferring is that the map can go stale: someone else may take
 * your seat between your tap and your Proceed. The server resolves that
 * honestly with a 409 listing exactly which seats were lost, and the handler at
 * the bottom of this file turns that into a specific, recoverable message
 * rather than "something went wrong".
 */

/** Enforced client-side too; the server enforces it as the real limit. */
const MAX_SEATS = 10;

export function SeatMapPage(): JSX.Element {
  const params = useParams<{ id: string }>();
  const eventId = Number(params.id);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { status: authStatus } = useAuth();

  const seatMapQuery = useQuery({
    queryKey: ['seatmap', eventId],
    queryFn: () => getSeatMap(eventId),
    enabled: Number.isFinite(eventId),
    /**
     * The map goes stale quickly — other people are booking against it right
     * now. 20s keeps it reasonably live without hammering the endpoint, and a
     * refetch on window focus covers the common "switched tabs for a minute"
     * case, which is when the map is most likely to be wrong.
     */
    staleTime: 20_000,
    refetchOnWindowFocus: true,
  });

  /** Local selection. Ids, not seat objects, so a refetch cannot desync them. */
  const [selectedIds, setSelectedIds] = useState<ReadonlySet<number>>(new Set());
  /** Shown inline when the user hits the 10-seat ceiling. */
  const [limitNotice, setLimitNotice] = useState<string | null>(null);
  /** Seats another user took from under us — flashed red after a 409. */
  const [lostSeatIds, setLostSeatIds] = useState<ReadonlySet<number>>(new Set());
  const [conflictMessage, setConflictMessage] = useState<string | null>(null);

  const flashTimerRef = useRef<number | null>(null);
  useEffect(() => {
    // Clear any pending timer if the user navigates away mid-flash.
    return () => {
      if (flashTimerRef.current !== null) window.clearTimeout(flashTimerRef.current);
    };
  }, []);

  /** Fast lookup from tier id → tier, built once per data change. */
  const tiersById = useMemo(() => {
    const map = new Map<number, Tier>();
    for (const tier of seatMapQuery.data?.tiers ?? []) map.set(tier.id, tier);
    return map;
  }, [seatMapQuery.data]);

  /** Every seat by id, so the summary bar can resolve labels and prices. */
  const seatsById = useMemo(() => {
    const map = new Map<number, Seat>();
    for (const row of seatMapQuery.data?.rows ?? []) {
      for (const seat of row.seats) map.set(seat.id, seat);
    }
    return map;
  }, [seatMapQuery.data]);

  /**
   * The selected seats, in map order rather than click order. Users read their
   * selection as "A5, A6, A7", so sorting by row then column is what they
   * expect; click order would show "A7, A5, A6" and look like a bug.
   */
  const selectedSeats = useMemo(() => {
    const result: Seat[] = [];
    for (const row of seatMapQuery.data?.rows ?? []) {
      for (const seat of row.seats) {
        if (selectedIds.has(seat.id)) result.push(seat);
      }
    }
    return result;
  }, [seatMapQuery.data, selectedIds]);

  const totalMinor = useMemo(
    () => selectedSeats.reduce((sum, seat) => sum + seat.priceMinor, 0),
    [selectedSeats],
  );

  /**
   * `useCallback` with a functional state update and an empty-ish dep list.
   * The identity of this function is what stops all ~200 memoised SeatButtons
   * from re-rendering on every click — if it were recreated each render the
   * memo comparator would fail for every seat and the memoisation would be
   * pure overhead.
   */
  const toggleSeat = useCallback((seat: Seat): void => {
    setLimitNotice(null);
    setSelectedIds((current) => {
      const next = new Set(current);
      if (next.has(seat.id)) {
        next.delete(seat.id);
        return next;
      }
      if (next.size >= MAX_SEATS) {
        setLimitNotice(
          `You can book up to ${String(MAX_SEATS)} seats in one transaction. Remove a seat to pick a different one.`,
        );
        return current; // unchanged reference: no re-render of the grid
      }
      next.add(seat.id);
      return next;
    });
  }, []);

  const removeSeat = useCallback((seatId: number): void => {
    setLimitNotice(null);
    setSelectedIds((current) => {
      const next = new Set(current);
      next.delete(seatId);
      return next;
    });
  }, []);

  /**
   * The one and only write on this screen.
   */
  const holdMutation = useMutation({
    mutationFn: (seatIds: number[]) => createHold(eventId, seatIds),
    onSuccess: (hold) => {
      // The holdId doubles as the pending booking's publicId, so the checkout
      // route needs nothing else.
      navigate(`/checkout/${hold.holdId}`);
    },
    onError: (error: unknown) => {
      if (!isApiError(error)) return;

      /**
       * THE 409 PATH — the interesting failure, and the reason the deferred-
       * write design needs care.
       *
       * The server tells us exactly which seat ids were lost. We:
       *   1. drop those ids from the selection (keeping the ones still ours),
       *   2. refetch the map so the lost seats show their true new state,
       *   3. flash them in the accent colour for a moment so the user's eye
       *      goes straight to what changed rather than hunting a diff.
       *
       * Holds are all-or-nothing, so nothing was reserved — there is no partial
       * state to unwind, and the user can simply pick again and press Proceed.
       */
      if (error.code === 'SEAT_UNAVAILABLE') {
        const lost = new Set(error.unavailableSeatIds);
        setLostSeatIds(lost);
        setSelectedIds((current) => {
          const next = new Set(current);
          for (const id of lost) next.delete(id);
          return next;
        });

        const labels = [...lost]
          .map((id) => seatsById.get(id)?.label ?? `#${String(id)}`)
          .join(', ');
        setConflictMessage(
          lost.size > 0
            ? `${labels} ${lost.size === 1 ? 'was' : 'were'} taken while you were choosing. Your other seats are still selected.`
            : error.message,
        );

        void queryClient.invalidateQueries({ queryKey: ['seatmap', eventId] });

        if (flashTimerRef.current !== null) window.clearTimeout(flashTimerRef.current);
        flashTimerRef.current = window.setTimeout(() => {
          setLostSeatIds(new Set());
        }, 2600);
        return;
      }

      if (error.code === 'SALES_CLOSED') {
        setConflictMessage('Booking has closed for this show.');
        return;
      }
      setConflictMessage(error.message);
    },
  });

  function onProceed(): void {
    setConflictMessage(null);
    if (authStatus !== 'authenticated') {
      // Send them to sign in, then straight back here. We do not attempt the
      // hold first: a guaranteed 401 would burn one of the hold rate limit's
      // 30/minute for nothing.
      navigate('/login', { state: { from: `/events/${String(eventId)}/seats` } });
      return;
    }
    holdMutation.mutate(selectedSeats.map((seat) => seat.id));
  }

  if (!Number.isFinite(eventId)) {
    return (
      <main className="mx-auto max-w-[1240px] px-4 py-10">
        <ErrorState error={new Error('bad id')} />
      </main>
    );
  }
  if (seatMapQuery.isPending) return <SeatMapSkeleton />;
  if (seatMapQuery.isError) {
    return (
      <main className="mx-auto max-w-[1240px] px-4 py-10">
        <ErrorState
          error={seatMapQuery.error}
          onRetry={() => {
            void seatMapQuery.refetch();
          }}
        />
      </main>
    );
  }

  const seatMap = seatMapQuery.data;

  const disabledReason =
    selectedSeats.length === 0
      ? 'Select at least one seat to continue.'
      : authStatus === 'booting'
        ? 'Checking your session…'
        : null;

  return (
    <main className="mx-auto max-w-[1240px] px-4 pb-4">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-ink-700 py-3">
        <div className="min-w-0">
          <Link
            to={`/events/${String(eventId)}`}
            className="label-micro transition-colors hover:text-flare-400"
          >
            &larr; Back to event
          </Link>
          <h1 className="display mt-1 text-[24px] text-fog-50">{seatMap.venueName}</h1>
        </div>

        {/* Live availability counters. Cheap to render, and they are what a
            user actually wants to know before scanning 200 seats. */}
        <dl className="flex gap-5">
          <Counter label="Available" value={seatMap.summary.available} tone="text-mint-500" />
          <Counter label="Held" value={seatMap.summary.held} tone="text-amber-500" />
          <Counter label="Booked" value={seatMap.summary.booked} tone="text-fog-400" />
        </dl>
      </div>

      {conflictMessage !== null ? (
        <div
          role="alert"
          className="mt-3 flex items-start gap-2 rounded border border-flare-500 bg-ink-850 px-3 py-2"
        >
          <span
            aria-hidden="true"
            className="mt-0.5 flex h-4 w-4 shrink-0 items-center justify-center rounded-full border border-flare-500 text-[9px] font-bold text-flare-500"
          >
            !
          </span>
          <p className="text-sm text-fog-200">{conflictMessage}</p>
        </div>
      ) : null}

      {limitNotice !== null ? (
        <p role="status" className="mt-3 rounded border border-ink-700 bg-ink-850 px-3 py-2 text-sm text-amber-500">
          {limitNotice}
        </p>
      ) : null}

      {/* The auditorium. `overflow-x-auto` so a wide hall scrolls sideways on a
          phone instead of shrinking the seats below tappable size. */}
      <div className="mt-6 overflow-x-auto pb-8">
        <div className="mx-auto min-w-[640px] max-w-[860px] px-8">
          <ScreenArc />
          <SeatGrid
            seatMap={seatMap}
            selectedIds={selectedIds}
            lostSeatIds={lostSeatIds}
            tiersById={tiersById}
            onToggle={toggleSeat}
          />
        </div>
      </div>

      <Legend tiers={seatMap.tiers} />

      <SelectionBar
        selected={selectedSeats}
        totalMinor={totalMinor}
        disabledReason={disabledReason}
        pending={holdMutation.isPending}
        onRemove={removeSeat}
        onProceed={onProceed}
      />
    </main>
  );
}

function Counter({
  label,
  value,
  tone,
}: {
  label: string;
  value: number;
  tone: string;
}): JSX.Element {
  return (
    <div className="text-right">
      <dt className="label-micro">{label}</dt>
      <dd className={`text-sm font-semibold ${tone}`}>{value}</dd>
    </div>
  );
}

/* ------------------------------------------------------------- the grid -- */

function SeatGrid({
  seatMap,
  selectedIds,
  lostSeatIds,
  tiersById,
  onToggle,
}: {
  seatMap: SeatMap;
  selectedIds: ReadonlySet<number>;
  lostSeatIds: ReadonlySet<number>;
  tiersById: ReadonlyMap<number, Tier>;
  onToggle: (seat: Seat) => void;
}): JSX.Element {
  // One column count for the whole hall, so seat 7 in row A sits above seat 7
  // in row B even when the rows have different lengths.
  const columnCount = useMemo(() => computeColumnCount(seatMap.rows), [seatMap.rows]);

  /**
   * Section dividers. The API gives each row a `section`; we render a heading
   * whenever it changes going back through the hall. Deriving it from the data
   * (rather than from the tier list) means the dividers are always in the right
   * place even if a hall interleaves sections oddly.
   */
  const rowsWithSectionStart = useMemo(() => {
    let previousSection: string | null = null;
    return seatMap.rows.map((row) => {
      const startsSection = row.section !== previousSection;
      previousSection = row.section;
      return { row, startsSection };
    });
  }, [seatMap.rows]);

  return (
    <div className="flex flex-col gap-[7px]">
      {rowsWithSectionStart.map(({ row, startsSection }, index) => {
        const geometry = computeRowGeometry(row, index, seatMap.rows.length);
        // The section's price comes from the first seat's tier, which is the
        // authoritative per-seat price rather than a guess from the tier list.
        const firstSeat = row.seats[0];
        const sectionTier = firstSeat === undefined ? undefined : tiersById.get(firstSeat.tierId);

        return (
          <div key={row.rowLabel}>
            {startsSection ? (
              <div className="mb-2 mt-5 flex items-center gap-3 first:mt-0">
                <span className="label-micro whitespace-nowrap">
                  {titleCase(row.section)}
                  {sectionTier !== undefined ? (
                    <span className="ml-2 text-fog-200">{formatMoney(sectionTier.priceMinor)}</span>
                  ) : null}
                </span>
                <span className="h-px flex-1 bg-ink-700" />
              </div>
            ) : null}

            <SeatRowView
              row={row}
              geometry={geometry}
              columnCount={columnCount}
              selectedIds={selectedIds}
              lostSeatIds={lostSeatIds}
              tiersById={tiersById}
              onToggle={onToggle}
            />
          </div>
        );
      })}
    </div>
  );
}

function SeatRowView({
  row,
  geometry,
  columnCount,
  selectedIds,
  lostSeatIds,
  tiersById,
  onToggle,
}: {
  row: SeatRow;
  geometry: ReturnType<typeof computeRowGeometry>;
  columnCount: number;
  selectedIds: ReadonlySet<number>;
  lostSeatIds: ReadonlySet<number>;
  tiersById: ReadonlyMap<number, Tier>;
  onToggle: (seat: Seat) => void;
}): JSX.Element {
  return (
    <div className="flex items-start gap-3">
      {/* Row labels on BOTH flanks. In a wide hall the left label is far from
          the right-hand seats, and in a real cinema the letters are painted at
          both ends of the row for exactly the same reason. */}
      <RowFlank label={row.rowLabel} />

      <div
        className="grid flex-1 justify-items-center"
        style={{
          // `colIndex` from the API already contains the centre-aisle gap, so
          // the aisle is just a column with no seat in it — no special-casing,
          // no "if (i === 6) render a spacer".
          gridTemplateColumns: `repeat(${String(columnCount)}, minmax(0, 22px))`,
          columnGap: '5px',
        }}
      >
        {row.seats.map((seat) => {
          const tier = tiersById.get(seat.tierId);
          return (
            <SeatButton
              key={seat.id}
              seat={seat}
              state={resolveSeatState(seat, selectedIds)}
              tierColour={tier?.colour ?? 'var(--fog-400)'}
              tierName={tier?.name ?? row.section}
              offsetY={seatOffsetY(seat.colIndex, geometry)}
              flashLost={lostSeatIds.has(seat.id)}
              onToggle={onToggle}
            />
          );
        })}
      </div>

      <RowFlank label={row.rowLabel} />
    </div>
  );
}

function RowFlank({ label }: { label: string }): JSX.Element {
  return (
    <span
      // Hidden from assistive tech: each seat's aria-label already contains its
      // row letter ("Seat A5"), so announcing the flank would just repeat it.
      aria-hidden="true"
      className="w-4 shrink-0 pt-1 text-center text-2xs font-semibold text-fog-400"
    >
      {label}
    </span>
  );
}

/** Maps an API status plus local selection onto the five visual states. */
function resolveSeatState(seat: Seat, selectedIds: ReadonlySet<number>): SeatVisualState {
  // Selection wins over AVAILABLE, and only over AVAILABLE — a seat that went
  // BOOKED under us after a refetch must render as booked even if it is still
  // in the local selection set (the 409 handler then prunes it).
  if (seat.status === 'AVAILABLE') {
    return selectedIds.has(seat.id) ? 'selected' : 'available';
  }
  if (seat.status === 'BOOKED') return 'booked';
  if (seat.status === 'BLOCKED') return 'blocked';
  return 'held';
}

/* ----------------------------------------------------------- skeletons -- */

/** Shaped like an auditorium, not like a spinner. */
function SeatMapSkeleton(): JSX.Element {
  return (
    <main className="mx-auto max-w-[1240px] px-4 py-6">
      <SkeletonBlock className="h-6 w-56 rounded" />
      <div className="mx-auto mt-10 max-w-[720px]">
        <SkeletonBlock className="mx-auto h-3 w-2/3 rounded-full" />
        <div className="mt-10 flex flex-col gap-[7px]">
          {Array.from({ length: 9 }, (_, rowIndex) => {
            const seatsPerRow = 16;
            // Reuses the real geometry helpers, so the skeleton bows exactly
            // like the map it stands in for — the whole point of a skeleton is
            // that nothing moves when the real thing arrives, and a flat
            // skeleton under a curved map would shift every seat.
            const geometry = computeRowGeometry(
              {
                rowLabel: '',
                rowIndex,
                section: '',
                seats: Array.from({ length: seatsPerRow }, (_, index) => ({
                  id: index,
                  label: '',
                  seatNumber: index + 1,
                  colIndex: index + 1,
                  tierId: 0,
                  priceMinor: 0,
                  status: 'AVAILABLE' as const,
                })),
              },
              rowIndex,
              9,
            );
            return (
              <div key={rowIndex} className="flex justify-center gap-[5px]">
                {Array.from({ length: seatsPerRow }, (_, seatIndex) => (
                  <SkeletonBlock
                    key={seatIndex}
                    className="h-[22px] w-[22px] rounded-sm"
                    style={{
                      transform: `translateY(${String(seatOffsetY(seatIndex + 1, geometry))}px)`,
                    }}
                  />
                ))}
              </div>
            );
          })}
        </div>
      </div>
    </main>
  );
}
