import { useId, useMemo, type ReactNode } from 'react';
import { createRandom, hashString } from '@/lib/hash';

/**
 * PROCEDURAL POSTER ARTWORK
 * =========================
 *
 * The API returns `posterUrl: null` for every event, and there is no image CDN
 * behind this project. The three obvious options were all bad:
 *   - grey placeholder boxes: says "unfinished" on every screen,
 *   - external stock photos: broken links, licensing questions, and they never
 *     match each other,
 *   - a solid colour with the title on it: readable, but visually dead.
 *
 * So the artwork is GENERATED from the title. Each poster is a small piece of
 * geometry drawn as inline SVG, with the treatment, the palette and every
 * offset derived from a hash of the title string. That gives two properties
 * that matter:
 *
 *   DETERMINISTIC — "Meridian" produces the same poster on every render, on
 *   every device, on the rail and on the detail page. Users learn to recognise
 *   a film by its artwork, which is the entire job of a poster.
 *
 *   VARIED — six treatments crossed with seven palettes and randomised
 *   geometry gives enough distinct output that a rail of six events never looks
 *   repetitive.
 *
 * The visual reference is minimal repertory-cinema poster design (A24,
 * Criterion, Mubi): a dark ground, one or two flat colours, one large geometric
 * gesture, condensed type set large and tight in the bottom-left corner, and a
 * film-grain overlay to stop the flat vectors looking like clip art. The grain
 * is the cheapest and most effective part — an `feTurbulence` filter at ~12%
 * opacity is the difference between "generated SVG" and "printed poster".
 */

/* --------------------------------------------------------------- palette -- */

interface Palette {
  /** Near-black ground, slightly tinted toward the accent so it never reads flat grey. */
  base: string;
  /** The dominant shape colour. */
  primary: string;
  /** A secondary colour for the smaller gesture. */
  secondary: string;
}

/**
 * Hand-picked rather than generated from random hues. A random-hue generator
 * reliably produces a few muddy or fluorescent combinations, and one ugly
 * poster in a rail undoes the work of the other five. Seven curated pairs, all
 * chosen to sit correctly against the app's near-black chrome and to avoid
 * colliding with the `--flare-500` accent (which the UI reserves for price and
 * selection, so the artwork must not compete with it).
 */
const PALETTES: readonly Palette[] = [
  { base: '#0B0D12', primary: '#E2603C', secondary: '#F0C55A' }, // ember / brass
  { base: '#0A0F12', primary: '#3F8F87', secondary: '#D8D2C0' }, // teal / bone
  { base: '#110A0E', primary: '#B8385A', secondary: '#EFD9C4' }, // crimson / sand
  { base: '#0C0B14', primary: '#4E63B8', secondary: '#9FD5C6' }, // cobalt / mint
  { base: '#0E0C09', primary: '#D9A441', secondary: '#7A4B2A' }, // ochre / umber
  { base: '#080F0D', primary: '#5FA85A', secondary: '#E4E1B8' }, // moss / chartreuse
  { base: '#0D0A10', primary: '#8A6FB0', secondary: '#E8A9A0' }, // heather / blush
];

/** How many drawing treatments exist. Kept next to the switch that uses it. */
const TREATMENT_COUNT = 6;

interface PosterIdentity {
  seed: number;
  palette: Palette;
  treatment: number;
}

/**
 * Turns a title into its stable visual identity.
 *
 * Note the two different derivations from one hash: the palette uses the LOW
 * bits and the treatment uses bits shifted down by 8. Using `% 7` and `% 6` on
 * the same raw number would correlate the two choices (their periods share
 * structure), so certain palettes would only ever appear with certain shapes.
 * Shifting first decorrelates them.
 */
function posterIdentity(title: string): PosterIdentity {
  const seed = hashString(title);
  return {
    seed,
    palette: PALETTES[seed % PALETTES.length] ?? PALETTES[0]!,
    treatment: (seed >>> 8) % TREATMENT_COUNT,
  };
}

/* ------------------------------------------------------------ treatments -- */

/**
 * Every treatment is a pure function of (palette, seeded RNG, width, height).
 * Taking width and height as arguments — rather than hard-coding the 400×600
 * poster box — is what lets the wide backdrop band on the event page reuse the
 * exact same six treatments at a 1600×520 aspect ratio, so a film's detail page
 * is visibly the same artwork as its poster.
 */
type Treatment = (p: Palette, rng: () => number, w: number, h: number) => ReactNode;

/** 0 — DIAGONAL SPLIT: one hard diagonal edge plus a thin parallel stripe. */
const diagonalSplit: Treatment = (p, rng, w, h) => {
  const leftY = h * (0.45 + rng() * 0.25);
  const rightY = h * (0.10 + rng() * 0.22);
  const stripeGap = h * 0.06;
  return (
    <>
      <polygon points={`0,${leftY} ${w},${rightY} ${w},${h} 0,${h}`} fill={p.primary} />
      <polygon
        points={`0,${leftY - stripeGap * 1.6} ${w},${rightY - stripeGap * 1.6} ${w},${rightY - stripeGap * 0.55} 0,${leftY - stripeGap * 0.55}`}
        fill={p.secondary}
        opacity={0.85}
      />
    </>
  );
};

/** 1 — CONCENTRIC ARCS: rings radiating from a point above the centre. */
const concentricArcs: Treatment = (p, rng, w, h) => {
  const cx = w * (0.30 + rng() * 0.4);
  const cy = h * (0.22 + rng() * 0.16);
  const step = Math.max(w, h) * 0.075;
  const rings = 9;
  return (
    <>
      <circle cx={cx} cy={cy} r={step * 0.62} fill={p.secondary} />
      {Array.from({ length: rings }, (_, index) => {
        const radius = step * (index + 1.4);
        return (
          <circle
            key={index}
            cx={cx}
            cy={cy}
            r={radius}
            fill="none"
            stroke={index % 3 === 0 ? p.secondary : p.primary}
            // Rings thicken outward, which reads as motion away from the centre.
            strokeWidth={1 + index * 0.55}
            opacity={0.92 - index * 0.075}
          />
        );
      })}
    </>
  );
};

/** 2 — OFFSET LINE GRID: vertical hairlines of unequal length. */
const lineGrid: Treatment = (p, rng, w, h) => {
  const columns = 26;
  const gap = w / columns;
  return (
    <>
      {Array.from({ length: columns }, (_, index) => {
        const x = gap * (index + 0.5);
        // Each line starts at a different height; the sine term keeps the tops
        // on a smooth wave rather than pure noise, which looks composed instead
        // of accidental.
        const wave = Math.sin((index / columns) * Math.PI * 1.6) * 0.22;
        const top = h * (0.18 + wave + rng() * 0.08);
        return (
          <line
            key={index}
            x1={x}
            y1={top}
            x2={x}
            y2={h * 0.92}
            stroke={index % 5 === 0 ? p.secondary : p.primary}
            strokeWidth={index % 5 === 0 ? 3 : 1.5}
            opacity={index % 5 === 0 ? 0.95 : 0.6}
          />
        );
      })}
    </>
  );
};

/** 3 — HALFTONE FIELD: a dot grid whose radius decays downward. */
const halftone: Treatment = (p, rng, w, h) => {
  const spacing = Math.max(w, h) / 26;
  const columns = Math.ceil(w / spacing);
  const rows = Math.ceil(h / spacing);
  const dots: ReactNode[] = [];
  for (let row = 0; row < rows; row += 1) {
    for (let column = 0; column < columns; column += 1) {
      // Dots are large at the top and vanish toward the bottom, which both
      // creates a gradient and clears space for the title block.
      const verticalFalloff = 1 - row / rows;
      const radius = spacing * 0.42 * verticalFalloff * (0.55 + rng() * 0.65);
      if (radius < 0.4) continue;
      dots.push(
        <circle
          key={`${String(row)}-${String(column)}`}
          cx={spacing * (column + 0.5)}
          cy={spacing * (row + 0.5)}
          r={radius}
          fill={row % 4 === 1 ? p.secondary : p.primary}
          opacity={0.35 + verticalFalloff * 0.6}
        />,
      );
    }
  }
  return <>{dots}</>;
};

/** 4 — STACKED BANDS: horizontal bars of unequal weight. */
const bands: Treatment = (p, rng, w, h) => {
  const count = 7;
  let cursor = h * 0.08;
  const rects: ReactNode[] = [];
  for (let index = 0; index < count && cursor < h * 0.86; index += 1) {
    const bandHeight = h * (0.02 + rng() * 0.085);
    // Bands are inset from alternating sides so the block has a ragged edge
    // rather than sitting as a solid rectangle.
    const inset = index % 2 === 0 ? 0 : w * (0.08 + rng() * 0.22);
    rects.push(
      <rect
        key={index}
        x={inset}
        y={cursor}
        width={w - inset - (index % 3 === 0 ? w * 0.14 * rng() : 0)}
        height={bandHeight}
        fill={index % 3 === 1 ? p.secondary : p.primary}
        opacity={0.55 + rng() * 0.45}
      />,
    );
    cursor += bandHeight + h * (0.015 + rng() * 0.04);
  }
  return <>{rects}</>;
};

/** 5 — OFF-CANVAS CIRCLE: one huge disc cropped by the frame, plus a ring. */
const bigCircle: Treatment = (p, rng, w, h) => {
  const radius = Math.max(w, h) * (0.42 + rng() * 0.16);
  const cx = w * (0.62 + rng() * 0.5); // deliberately > 1.0 sometimes: crops out
  const cy = h * (0.18 + rng() * 0.2);
  return (
    <>
      <circle cx={cx} cy={cy} r={radius} fill={p.primary} />
      <circle
        cx={w * 0.22}
        cy={h * (0.55 + rng() * 0.2)}
        r={radius * 0.42}
        fill="none"
        stroke={p.secondary}
        strokeWidth={Math.max(2, w * 0.008)}
      />
    </>
  );
};

const TREATMENTS: readonly Treatment[] = [
  diagonalSplit,
  concentricArcs,
  lineGrid,
  halftone,
  bands,
  bigCircle,
];

/* ------------------------------------------------------------ title wrap -- */

/**
 * Word-wraps the title and picks a font size that fills the block.
 *
 * SVG `<text>` does not wrap — there is no equivalent of CSS line-breaking — so
 * the lines have to be computed and emitted as separate `<tspan>`s. That means
 * measuring text without a DOM, which we approximate.
 *
 * Bebas Neue is a condensed face whose glyphs average roughly 0.42 of the font
 * size in advance width (measured by hand from a rendered sample). That
 * constant is good enough because we only need to choose between a few discrete
 * sizes, and we bias downward: a title one step too small looks intentional,
 * one step too large overflows the poster and looks broken.
 *
 * The loop tries the largest size first and steps down until the title fits in
 * the allowed number of lines with no single word overflowing.
 */
const BEBAS_ADVANCE_RATIO = 0.42;

function wrapTitle(
  title: string,
  availableWidth: number,
  maxLines: number,
  sizes: readonly number[],
): { lines: string[]; fontSize: number } {
  const words = title.trim().split(/\s+/).filter((word) => word.length > 0);
  // Bebas Neue has no lowercase glyphs; uppercasing keeps our width estimate
  // honest and matches how the face actually renders.
  const upperWords = words.map((word) => word.toUpperCase());

  for (const fontSize of sizes) {
    const maxChars = Math.floor(availableWidth / (fontSize * BEBAS_ADVANCE_RATIO));
    if (maxChars < 3) continue;

    // A single word longer than the line can never fit at this size.
    if (upperWords.some((word) => word.length > maxChars)) continue;

    // Greedy wrap: keep adding words until the next one would overflow.
    const lines: string[] = [];
    let current = '';
    for (const word of upperWords) {
      const candidate = current === '' ? word : `${current} ${word}`;
      if (candidate.length <= maxChars) {
        current = candidate;
      } else {
        lines.push(current);
        current = word;
      }
    }
    if (current !== '') lines.push(current);

    if (lines.length <= maxLines) return { lines, fontSize };
  }

  // Fallback: nothing fitted (a pathological single long word). Use the
  // smallest size and one truncated line rather than rendering nothing.
  const smallest = sizes[sizes.length - 1] ?? 24;
  const maxChars = Math.max(4, Math.floor(availableWidth / (smallest * BEBAS_ADVANCE_RATIO)));
  const joined = upperWords.join(' ');
  return {
    lines: [joined.length > maxChars ? `${joined.slice(0, maxChars - 1)}…` : joined],
    fontSize: smallest,
  };
}

/* ----------------------------------------------------------- the poster -- */

interface PosterArtProps {
  title: string;
  /** Rendered as a small line above the title, e.g. the event subtitle. */
  eyebrow?: string | null | undefined;
  /** Hide the title block — used where the surrounding card prints the title. */
  showTitle?: boolean;
  className?: string;
}

const POSTER_WIDTH = 400;
const POSTER_HEIGHT = 600; // 2:3, the standard one-sheet ratio.

export function PosterArt({
  title,
  eyebrow,
  showTitle = true,
  className,
}: PosterArtProps): JSX.Element {
  /**
   * `useId` gives a per-instance prefix. SVG filter and gradient references are
   * document-global (`filter="url(#grain)"`), so two posters on the same page
   * with the same hard-coded id would have the second silently steal the
   * first's filter. The regex strips React's colons, which are legal in an id
   * attribute but break `url(#...)` parsing in some engines.
   */
  const rawId = useId();
  const uid = rawId.replace(/[^a-zA-Z0-9]/g, '');

  // Memoised on the title alone: this does real work (the halftone treatment
  // emits several hundred circles) and the title is the only input.
  const art = useMemo(() => {
    const { seed, palette, treatment } = posterIdentity(title);
    const rng = createRandom(seed);
    const draw = TREATMENTS[treatment] ?? diagonalSplit;
    return {
      palette,
      shapes: draw(palette, rng, POSTER_WIDTH, POSTER_HEIGHT),
    };
  }, [title]);

  const typography = useMemo(
    () =>
      wrapTitle(
        title,
        POSTER_WIDTH - 56, // 28px of optical margin on each side
        3,
        [76, 66, 58, 50, 42, 36, 30],
      ),
    [title],
  );

  const lineHeight = typography.fontSize * 0.86; // tight leading, poster-style
  const baseline = POSTER_HEIGHT - 34;

  return (
    <svg
      viewBox={`0 0 ${String(POSTER_WIDTH)} ${String(POSTER_HEIGHT)}`}
      className={className}
      // The card that contains this prints the title as real text, so the
      // artwork itself is decoration as far as a screen reader is concerned.
      role="presentation"
      aria-hidden="true"
      preserveAspectRatio="xMidYMid slice"
    >
      <defs>
        {/* Film grain. fractalNoise (not turbulence) gives an even, sandy
            texture rather than smoke-like wisps; desaturating it to greyscale
            keeps it from tinting the artwork. */}
        <filter id={`grain-${uid}`} x="0" y="0" width="100%" height="100%">
          <feTurbulence
            type="fractalNoise"
            baseFrequency="0.85"
            numOctaves={3}
            stitchTiles="stitch"
            result="noise"
          />
          <feColorMatrix in="noise" type="saturate" values="0" />
        </filter>

        {/* A bottom-up scrim. Without this the title would sit unreadably on
            top of whichever shape the treatment happened to put there — this
            guarantees contrast regardless of the generated geometry. */}
        <linearGradient id={`scrim-${uid}`} x1="0" y1="1" x2="0" y2="0">
          <stop offset="0%" stopColor={art.palette.base} stopOpacity="0.96" />
          <stop offset="42%" stopColor={art.palette.base} stopOpacity="0.72" />
          <stop offset="100%" stopColor={art.palette.base} stopOpacity="0" />
        </linearGradient>

        {/* A very slight vignette. Real printed posters are never evenly lit. */}
        <radialGradient id={`vignette-${uid}`} cx="50%" cy="38%" r="78%">
          <stop offset="55%" stopColor="#000000" stopOpacity="0" />
          <stop offset="100%" stopColor="#000000" stopOpacity="0.5" />
        </radialGradient>
      </defs>

      <rect width={POSTER_WIDTH} height={POSTER_HEIGHT} fill={art.palette.base} />
      {art.shapes}
      <rect width={POSTER_WIDTH} height={POSTER_HEIGHT} fill={`url(#vignette-${uid})`} />

      {showTitle ? (
        <>
          <rect
            y={POSTER_HEIGHT * 0.42}
            width={POSTER_WIDTH}
            height={POSTER_HEIGHT * 0.58}
            fill={`url(#scrim-${uid})`}
          />
          {eyebrow !== null && eyebrow !== undefined && eyebrow !== '' ? (
            <text
              x={28}
              // Sits above the whole title block, however many lines it took.
              y={baseline - lineHeight * (typography.lines.length - 1) - typography.fontSize * 0.78}
              fill="#FFFFFF"
              fontFamily="Inter, system-ui, sans-serif"
              fontSize={13}
              fontWeight={600}
              letterSpacing="1.4"
              opacity={0.72}
            >
              {eyebrow.toUpperCase()}
            </text>
          ) : null}
          <text
            x={28}
            fill="#FFFFFF"
            fontFamily="'Bebas Neue', Impact, sans-serif"
            fontSize={typography.fontSize}
            letterSpacing="0.5"
          >
            {typography.lines.map((line, index) => (
              <tspan
                key={line + String(index)}
                x={28}
                // Lines are positioned from the LAST line upward so the block is
                // bottom-aligned: a 3-line title grows up into the poster rather
                // than pushing its final line off the bottom edge.
                y={baseline - lineHeight * (typography.lines.length - 1 - index)}
              >
                {line}
              </tspan>
            ))}
          </text>
        </>
      ) : null}

      {/* Grain last, over everything including the type, at low opacity. */}
      <rect
        width={POSTER_WIDTH}
        height={POSTER_HEIGHT}
        filter={`url(#grain-${uid})`}
        opacity={0.13}
        style={{ mixBlendMode: 'overlay' }}
      />
    </svg>
  );
}

/* --------------------------------------------------------- wide backdrop -- */

interface BackdropArtProps {
  title: string;
  className?: string;
}

const BACKDROP_WIDTH = 1600;
const BACKDROP_HEIGHT = 520;

/**
 * The same identity and the same treatment, drawn at a cinematic wide ratio for
 * the event detail page's header band. Because the palette and treatment come
 * from the same `posterIdentity(title)` call, the detail page is unmistakably
 * the same artwork as the rail card the user clicked — which is the whole point
 * of making it deterministic.
 */
export function BackdropArt({ title, className }: BackdropArtProps): JSX.Element {
  const rawId = useId();
  const uid = rawId.replace(/[^a-zA-Z0-9]/g, '');

  const art = useMemo(() => {
    const { seed, palette, treatment } = posterIdentity(title);
    const rng = createRandom(seed);
    const draw = TREATMENTS[treatment] ?? diagonalSplit;
    return { palette, shapes: draw(palette, rng, BACKDROP_WIDTH, BACKDROP_HEIGHT) };
  }, [title]);

  return (
    <svg
      viewBox={`0 0 ${String(BACKDROP_WIDTH)} ${String(BACKDROP_HEIGHT)}`}
      className={className}
      role="presentation"
      aria-hidden="true"
      preserveAspectRatio="xMidYMid slice"
    >
      <defs>
        <filter id={`bgrain-${uid}`} x="0" y="0" width="100%" height="100%">
          <feTurbulence
            type="fractalNoise"
            baseFrequency="0.8"
            numOctaves={3}
            stitchTiles="stitch"
            result="noise"
          />
          <feColorMatrix in="noise" type="saturate" values="0" />
        </filter>
        {/* Fades the band into the page background at the bottom and on the
            left, so the title block laid over it stays readable and the band
            has no hard bottom edge. */}
        <linearGradient id={`bfade-${uid}`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="35%" stopColor="var(--ink-950)" stopOpacity="0" />
          <stop offset="100%" stopColor="var(--ink-950)" stopOpacity="1" />
        </linearGradient>
        <linearGradient id={`bside-${uid}`} x1="0" y1="0" x2="1" y2="0">
          <stop offset="0%" stopColor="var(--ink-950)" stopOpacity="0.92" />
          <stop offset="55%" stopColor="var(--ink-950)" stopOpacity="0.25" />
          <stop offset="100%" stopColor="var(--ink-950)" stopOpacity="0" />
        </linearGradient>
      </defs>

      <rect width={BACKDROP_WIDTH} height={BACKDROP_HEIGHT} fill={art.palette.base} />
      {art.shapes}
      <rect width={BACKDROP_WIDTH} height={BACKDROP_HEIGHT} fill={`url(#bside-${uid})`} />
      <rect width={BACKDROP_WIDTH} height={BACKDROP_HEIGHT} fill={`url(#bfade-${uid})`} />
      <rect
        width={BACKDROP_WIDTH}
        height={BACKDROP_HEIGHT}
        filter={`url(#bgrain-${uid})`}
        opacity={0.12}
        style={{ mixBlendMode: 'overlay' }}
      />
    </svg>
  );
}
