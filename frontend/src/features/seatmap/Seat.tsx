import { memo } from 'react';
import type { Seat as SeatModel } from '@/api/schemas';
import { formatMoneySpoken } from '@/lib/format';
import { titleCase } from '@/lib/format';

/**
 * One seat.
 *
 * It is a real `<button>`, not a styled `<div>`. That single choice gives us
 * keyboard focus, Enter/Space activation, the `disabled` semantics that stop a
 * booked seat from being reachable by Tab, and screen-reader button semantics —
 * all for free and all correct. Rebuilding that on a div takes `tabIndex`, two
 * key handlers, `role`, and `aria-disabled`, and is still worse.
 *
 * `aria-pressed` (rather than `aria-selected`) is the right attribute because
 * a seat is a TOGGLE button: press it, it stays pressed. `aria-selected`
 * belongs to options inside a listbox/grid widget, which this is not.
 */

export type SeatVisualState = 'available' | 'selected' | 'booked' | 'blocked' | 'held';

interface SeatButtonProps {
  seat: SeatModel;
  state: SeatVisualState;
  /** The tier's colour from the API, used for the AVAILABLE outline. */
  tierColour: string;
  tierName: string;
  /** Pixels to push this seat down the page — the auditorium curve. */
  offsetY: number;
  /** True briefly after a 409, to flash the seats that were taken from us. */
  flashLost: boolean;
  onToggle: (seat: SeatModel) => void;
}

function SeatButtonInner({
  seat,
  state,
  tierColour,
  tierName,
  offsetY,
  flashLost,
  onToggle,
}: SeatButtonProps): JSX.Element {
  const selectable = state === 'available' || state === 'selected';

  /**
   * The five states are given genuinely different *shapes and fills*, not five
   * shades of the same thing. That matters for the ~8% of men with a colour
   * vision deficiency: an outlined seat, a solid red seat, a flat grey seat and
   * a struck-through seat are distinguishable without any colour perception at
   * all.
   */
  const stateClasses: Record<SeatVisualState, string> = {
    // Outline only, so a full house reads as a field of empty boxes and the
    // eye finds the gaps immediately.
    available: 'bg-transparent border hover:bg-ink-800 cursor-pointer',
    // Filled accent + a small scale-up, so a selection is unmistakable even in
    // peripheral vision while the user reads the summary bar.
    selected: 'bg-flare-500 border border-flare-500 text-white scale-110 cursor-pointer',
    // Solid, borderless, low-contrast: permanently gone, recedes visually.
    booked: 'bg-ink-600 border-0 text-ink-600 cursor-not-allowed',
    // Struck through (see the SVG below): not a seat at all — a pillar, a gap.
    blocked: 'bg-ink-800 border-0 cursor-not-allowed',
    // Amber outline + slow pulse: someone else's live hold, which may expire
    // and free up while the user is looking at the map.
    held: 'bg-transparent border border-amber-500 animate-hold-pulse cursor-not-allowed',
  };

  const label = [
    `Seat ${seat.label}`,
    titleCase(tierName),
    formatMoneySpoken(seat.priceMinor),
    state === 'selected' ? 'selected' : state,
  ].join(', ');

  return (
    <button
      type="button"
      // `disabled` (not just aria-disabled): a booked seat should not be a Tab
      // stop at all. Tabbing through 60 unusable seats to reach an available
      // one is a genuinely hostile keyboard experience.
      disabled={!selectable}
      aria-pressed={selectable ? state === 'selected' : undefined}
      aria-label={label}
      title={`${seat.label} · ${titleCase(tierName)}`}
      onClick={() => {
        // NO NETWORK CALL. See SeatMapPage for the full reasoning: selection is
        // local state until the user presses Proceed.
        if (selectable) onToggle(seat);
      }}
      style={{
        gridColumn: seat.colIndex,
        // The curve. A transform, so the grid's own geometry is untouched.
        transform: `translateY(${String(offsetY)}px)`,
        // Only AVAILABLE seats take the tier colour; every other state has a
        // fixed meaning that must not vary by tier.
        borderColor: state === 'available' ? tierColour : undefined,
      }}
      className={[
        'relative flex h-[22px] w-[22px] items-center justify-center rounded-sm',
        'text-[9px] font-semibold leading-none transition-[background-color,transform] duration-150',
        stateClasses[state],
        flashLost ? 'animate-lost-flash !border-flare-500 !bg-flare-500' : '',
      ].join(' ')}
    >
      {state === 'blocked' ? (
        // The diagonal strike, drawn rather than styled with a border trick so
        // it scales cleanly and needs no pseudo-element.
        <svg viewBox="0 0 10 10" className="h-full w-full" aria-hidden="true">
          <line x1="1.5" y1="8.5" x2="8.5" y2="1.5" stroke="var(--ink-600)" strokeWidth="1.4" />
        </svg>
      ) : (
        // The seat number, shown only on selectable seats — printing numbers on
        // booked seats adds noise to exactly the seats you cannot use.
        <span aria-hidden="true" className={selectable ? 'opacity-80' : 'opacity-0'}>
          {seat.seatNumber}
        </span>
      )}
    </button>
  );
}

/**
 * Memoised because a 200-seat map re-renders on every single click otherwise.
 * The comparator is explicit rather than the default shallow one so that the
 * `onToggle` callback identity (stable via useCallback in the parent) and the
 * seat object identity (stable across renders while the query data is cached)
 * are the only things that could ever force a re-render, and neither changes on
 * an unrelated seat's click.
 */
export const SeatButton = memo(SeatButtonInner, (prev, next) => {
  return (
    prev.seat === next.seat &&
    prev.state === next.state &&
    prev.offsetY === next.offsetY &&
    prev.tierColour === next.tierColour &&
    prev.flashLost === next.flashLost &&
    prev.onToggle === next.onToggle
  );
});
