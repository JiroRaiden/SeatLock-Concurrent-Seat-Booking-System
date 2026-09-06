import type { Tier } from '@/api/schemas';
import { formatMoney, titleCase } from '@/lib/format';

/**
 * The legend. Not optional decoration: the map uses five visual states and
 * three tier colours, and without a key the user has to guess what an amber
 * outline means. Real ticketing sites all carry one.
 *
 * Each swatch is rendered with the SAME classes as the real seat, so the legend
 * cannot drift out of sync with the map by being styled separately.
 */
export function Legend({ tiers }: { tiers: readonly Tier[] }): JSX.Element {
  return (
    <div className="flex flex-wrap items-center gap-x-5 gap-y-2 border-t border-ink-700 pt-3">
      <span className="label-micro">Key</span>

      <LegendItem label="Available">
        <span className="h-[14px] w-[14px] rounded-sm border border-tier-classic" />
      </LegendItem>

      <LegendItem label="Selected">
        <span className="h-[14px] w-[14px] rounded-sm border border-flare-500 bg-flare-500" />
      </LegendItem>

      <LegendItem label="Booked">
        <span className="h-[14px] w-[14px] rounded-sm bg-ink-600" />
      </LegendItem>

      <LegendItem label="Held by someone else">
        <span className="h-[14px] w-[14px] animate-hold-pulse rounded-sm border border-amber-500" />
      </LegendItem>

      <LegendItem label="Not a seat">
        <span className="relative h-[14px] w-[14px] rounded-sm bg-ink-800">
          <svg viewBox="0 0 10 10" className="h-full w-full" aria-hidden="true">
            <line x1="1.5" y1="8.5" x2="8.5" y2="1.5" stroke="var(--ink-600)" strokeWidth="1.4" />
          </svg>
        </span>
      </LegendItem>

      {/* Tier colours come from the API so the legend always matches whatever
          the backend actually sent, rather than a hard-coded copy of it. */}
      <span className="ml-auto flex flex-wrap items-center gap-x-4 gap-y-2">
        {tiers.map((tier) => (
          <LegendItem key={tier.id} label={`${titleCase(tier.name)} · ${formatMoney(tier.priceMinor)}`}>
            <span
              className="h-[14px] w-[14px] rounded-sm border"
              style={{ borderColor: tier.colour ?? 'var(--fog-400)' }}
            />
          </LegendItem>
        ))}
      </span>
    </div>
  );
}

function LegendItem({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}): JSX.Element {
  return (
    <span className="flex items-center gap-1.5 text-xs text-fog-400">
      <span aria-hidden="true" className="flex items-center">
        {children}
      </span>
      {label}
    </span>
  );
}
