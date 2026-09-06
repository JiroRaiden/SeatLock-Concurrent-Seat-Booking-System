import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';

/**
 * A horizontally scrolling strip of cards — the BookMyShow row pattern.
 *
 * Why a rail and not a grid: a grid commits vertical space proportional to the
 * number of items, so five categories of six events becomes an endless page. A
 * rail gives every category a fixed, equal amount of vertical space and lets
 * breadth live on the horizontal axis, which is how every real ticketing
 * homepage is laid out.
 *
 * Scroll snapping (`snap-x snap-mandatory` on the strip, `snap-start` on each
 * card) is what makes it feel like a product rather than an overflow div: the
 * strip settles with a card flush to the left edge instead of clipping one in
 * half.
 */
export function Rail({
  title,
  subtitle,
  children,
}: {
  title: string;
  subtitle?: string;
  children: ReactNode;
}): JSX.Element {
  const scrollerRef = useRef<HTMLDivElement | null>(null);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(false);

  /**
   * Arrow buttons are disabled at each end rather than hidden. A control that
   * appears and disappears makes the layout twitch as you scroll; one that
   * greys out stays put and still tells you where you are.
   */
  const updateArrows = useCallback((): void => {
    const element = scrollerRef.current;
    if (element === null) return;
    setCanScrollLeft(element.scrollLeft > 4);
    // The 4px slack absorbs sub-pixel rounding, which otherwise leaves the
    // right arrow enabled forever at the end of the strip.
    setCanScrollRight(element.scrollLeft + element.clientWidth < element.scrollWidth - 4);
  }, []);

  useEffect(() => {
    updateArrows();
    const element = scrollerRef.current;
    if (element === null) return;
    element.addEventListener('scroll', updateArrows, { passive: true });
    window.addEventListener('resize', updateArrows);
    return () => {
      element.removeEventListener('scroll', updateArrows);
      window.removeEventListener('resize', updateArrows);
    };
    // `children` is in the dep list because arrow availability depends on how
    // many cards are in the strip, which changes when a query resolves.
  }, [updateArrows, children]);

  function scrollByPage(direction: -1 | 1): void {
    const element = scrollerRef.current;
    if (element === null) return;
    // Scroll by ~85% of a viewport width rather than 100%, so one card stays
    // visible across the jump and the user keeps their place.
    element.scrollBy({ left: direction * element.clientWidth * 0.85, behavior: 'smooth' });
  }

  return (
    <section className="py-5">
      <div className="mb-3 flex items-end justify-between gap-4">
        <div>
          <h2 className="display text-[22px] text-fog-50">{title}</h2>
          {subtitle !== undefined ? (
            <p className="mt-0.5 text-xs text-fog-400">{subtitle}</p>
          ) : null}
        </div>

        <div className="hidden shrink-0 gap-1 sm:flex">
          <RailArrow
            direction="left"
            disabled={!canScrollLeft}
            onClick={() => {
              scrollByPage(-1);
            }}
          />
          <RailArrow
            direction="right"
            disabled={!canScrollRight}
            onClick={() => {
              scrollByPage(1);
            }}
          />
        </div>
      </div>

      <div ref={scrollerRef} className="rail">
        {children}
      </div>
    </section>
  );
}

/**
 * The chevron is an inline SVG, not an emoji or an icon font. Emoji render as a
 * different glyph on every OS and read as informal; an icon font is a network
 * request and a flash of missing glyphs for two shapes we can draw in one path.
 */
function RailArrow({
  direction,
  disabled,
  onClick,
}: {
  direction: 'left' | 'right';
  disabled: boolean;
  onClick: () => void;
}): JSX.Element {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      aria-label={direction === 'left' ? 'Scroll left' : 'Scroll right'}
      className="flex h-7 w-7 items-center justify-center rounded border border-ink-700 bg-ink-850 text-fog-200 transition-colors hover:border-ink-600 hover:text-fog-50 disabled:border-ink-800 disabled:text-ink-600"
    >
      <svg viewBox="0 0 24 24" className="h-3.5 w-3.5" fill="none" aria-hidden="true">
        <path
          d={direction === 'left' ? 'M15 5 L8 12 L15 19' : 'M9 5 L16 12 L9 19'}
          stroke="currentColor"
          strokeWidth={2.2}
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
    </button>
  );
}
