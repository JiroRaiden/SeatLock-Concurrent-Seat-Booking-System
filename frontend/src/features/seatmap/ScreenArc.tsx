/**
 * The screen indicator at the top of the auditorium.
 *
 * Two elements stacked:
 *   1. a wide, very shallow arc — the screen itself, drawn as an SVG path with
 *      a single quadratic curve, bowing the same way the seat rows bow so the
 *      two read as concentric;
 *   2. a soft light spill beneath it, which is the thing that actually
 *      communicates "the picture comes from up there". It is a wide, heavily
 *      blurred radial gradient at low opacity — the one place in the whole app
 *      that uses a blur, and it is depicting light, not decorating a panel.
 *
 * The label "SCREEN THIS WAY" is the phrasing Indian multiplex booking flows
 * actually use, and it resolves the ambiguity of which end of the map is the
 * front better than an arrow would.
 */
export function ScreenArc(): JSX.Element {
  return (
    <div className="relative mx-auto mb-9 w-full max-w-[560px] select-none">
      <svg
        viewBox="0 0 560 46"
        className="w-full"
        role="img"
        aria-label="The screen is at this end of the auditorium"
      >
        <defs>
          {/* The screen edge is brightest in the middle and fades at the ends,
              which suggests a curved surface catching light. */}
          <linearGradient id="screen-edge" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%" stopColor="var(--fog-400)" stopOpacity="0.15" />
            <stop offset="50%" stopColor="var(--fog-050)" stopOpacity="0.9" />
            <stop offset="100%" stopColor="var(--fog-400)" stopOpacity="0.15" />
          </linearGradient>

          {/* The light spill. An ellipse under the screen, blurred hard. */}
          <radialGradient id="screen-glow" cx="50%" cy="0%" r="72%">
            <stop offset="0%" stopColor="var(--fog-050)" stopOpacity="0.16" />
            <stop offset="55%" stopColor="var(--fog-050)" stopOpacity="0.05" />
            <stop offset="100%" stopColor="var(--fog-050)" stopOpacity="0" />
          </radialGradient>
        </defs>

        {/* The spill is drawn first so the screen edge sits crisply on top. */}
        <ellipse cx="280" cy="10" rx="270" ry="40" fill="url(#screen-glow)" />

        {/* A single quadratic Bézier: start left, one control point pulled up
            in the middle, end right. `Q` with a control point at y = −10 gives
            an apex around y = 3 — a 7px rise over 520px, which is the shallow
            bow a real screen has. */}
        <path
          d="M 20 14 Q 280 -10 540 14"
          fill="none"
          stroke="url(#screen-edge)"
          strokeWidth="3"
          strokeLinecap="round"
        />
      </svg>

      <p className="label-micro mt-1 text-center">Screen this way</p>
    </div>
  );
}
