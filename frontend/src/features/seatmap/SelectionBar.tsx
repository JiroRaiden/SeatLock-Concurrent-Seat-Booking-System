import type { Seat } from '@/api/schemas';
import { formatMoney } from '@/lib/format';

/**
 * The sticky bottom bar: what you picked, what it costs, and the commit button.
 *
 * It is sticky rather than fixed so it participates in the page's normal flow
 * and cannot cover the last row of seats — the map's bottom padding accounts
 * for its height.
 *
 * The disabled Proceed button always carries a REASON. A button that is greyed
 * out with no explanation is one of the most common usability failures in
 * booking flows: the user cannot tell whether the app is broken, still loading,
 * or waiting for them. Here the reason sits directly beside it.
 */
export function SelectionBar({
  selected,
  totalMinor,
  disabledReason,
  pending,
  onRemove,
  onProceed,
}: {
  selected: readonly Seat[];
  totalMinor: number;
  /** null means the button is enabled. */
  disabledReason: string | null;
  pending: boolean;
  onRemove: (seatId: number) => void;
  onProceed: () => void;
}): JSX.Element {
  return (
    // Fully opaque, not translucent: the seat map scrolls underneath this bar,
    // and seats showing through it would be both ugly and confusing.
    <div className="sticky bottom-0 z-30 border-t border-ink-700 bg-ink-900">
      <div className="mx-auto flex max-w-[1240px] flex-wrap items-center gap-x-6 gap-y-3 px-4 py-3">
        <div className="min-w-0 flex-1">
          <p className="label-micro">
            {selected.length === 0
              ? 'No seats selected'
              : `${String(selected.length)} seat${selected.length === 1 ? '' : 's'} selected`}
          </p>

          {selected.length === 0 ? (
            <p className="mt-1 text-sm text-fog-400">Tap a seat on the map to begin.</p>
          ) : (
            <ul className="mt-1.5 flex flex-wrap gap-1.5">
              {selected.map((seat) => (
                <li key={seat.id}>
                  {/* Each chip is a button that removes the seat — the map is
                      dense enough that hunting for the seat you mis-tapped is
                      slower than clicking its chip. */}
                  <button
                    type="button"
                    onClick={() => {
                      onRemove(seat.id);
                    }}
                    aria-label={`Remove seat ${seat.label} from selection`}
                    className="pill border border-ink-700 bg-ink-800 pr-1.5 text-fog-50 transition-colors hover:border-flare-500 hover:text-flare-400"
                  >
                    {seat.label}
                    <svg viewBox="0 0 12 12" className="h-2.5 w-2.5" aria-hidden="true">
                      <path
                        d="M3 3 L9 9 M9 3 L3 9"
                        stroke="currentColor"
                        strokeWidth="1.8"
                        strokeLinecap="round"
                      />
                    </svg>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>

        <div className="text-right">
          <p className="label-micro">Total</p>
          <p className="display text-[26px] leading-none text-flare-500">
            {formatMoney(totalMinor)}
          </p>
        </div>

        <div className="flex flex-col items-end gap-1">
          <button
            type="button"
            className="btn-primary h-10 w-[168px]"
            disabled={disabledReason !== null || pending}
            onClick={onProceed}
          >
            {pending ? 'Holding seats…' : 'Proceed'}
          </button>
          {disabledReason !== null ? (
            <p className="max-w-[220px] text-right text-2xs text-fog-400">{disabledReason}</p>
          ) : (
            <p className="text-2xs text-fog-400">Seats are held for 8 minutes.</p>
          )}
        </div>
      </div>
    </div>
  );
}
