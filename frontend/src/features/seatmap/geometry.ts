import type { SeatRow } from '@/api/schemas';

/**
 * THE AUDITORIUM CURVE
 * ====================
 *
 * A real cinema does not have flat rows. The seats bow away from the screen so
 * that every seat is roughly the same distance from it and every seat faces it
 * — the row is an arc, not a line. Drawing flat rows is the single thing that
 * makes a seat map look like a spreadsheet instead of a room, so we reproduce
 * the arc.
 *
 * THE MATHS
 * ---------
 * For a seat at column `c` in a row whose columns span `[min, max]`:
 *
 *     centre = (min + max) / 2                  the row's midpoint
 *     half   = (max - min) / 2                  distance from centre to an end
 *     t      = (c - centre) / half              normalised: -1 … 0 … +1
 *     dy     = depth * t²                       the vertical push, in pixels
 *
 * The key term is `t²`. Squaring does three things at once:
 *   - it is zero at the centre of the row, so the middle seat does not move;
 *   - it is symmetric, so the left and right ends move by the same amount
 *     (this is why we square instead of using `t` directly — a linear term
 *     would tilt the row rather than bow it);
 *   - it grows slowly near the middle and quickly at the edges, which is the
 *     shape of a parabola.
 *
 * A parabola is not literally a circular arc, but over the ~40° a cinema row
 * subtends the two are visually indistinguishable, and a parabola costs one
 * multiply per seat instead of a trig call. `y = x²` is the right amount of
 * cleverness here.
 *
 * The sign convention: `dy` is POSITIVE, i.e. seats move DOWN the page, away
 * from the screen, which sits at the top. So the row's ends sag away from the
 * screen and the centre of the row is nearest it — exactly like a real
 * auditorium seen in plan view.
 *
 * `depth` scales with row index so back rows bow more than front rows, which
 * is also true of real rooms (the arc's radius is measured from the screen, so
 * a row further back subtends more of it).
 */

/** Maximum vertical displacement, in pixels, at the ends of the LAST row. */
const MAX_CURVE_DEPTH_PX = 26;
/** Displacement at the ends of the FIRST row. Front rows are nearly flat. */
const MIN_CURVE_DEPTH_PX = 8;

export interface RowGeometry {
  /** Smallest colIndex present in this row. */
  minCol: number;
  /** Largest colIndex present in this row. */
  maxCol: number;
  /** Curve depth applied at this row's extreme columns. */
  depth: number;
}

/**
 * Precomputes per-row constants once, rather than per seat. With ~200 seats
 * this is not a performance necessity, but it keeps `seatOffsetY` a pure two-
 * line function that is easy to reason about.
 */
export function computeRowGeometry(row: SeatRow, rowPosition: number, rowCount: number): RowGeometry {
  // `noUncheckedIndexedAccess` makes `row.seats[0]` possibly-undefined, so we
  // reduce instead of indexing — which also handles rows with holes in them.
  let minCol = Number.POSITIVE_INFINITY;
  let maxCol = Number.NEGATIVE_INFINITY;
  for (const seat of row.seats) {
    if (seat.colIndex < minCol) minCol = seat.colIndex;
    if (seat.colIndex > maxCol) maxCol = seat.colIndex;
  }
  if (!Number.isFinite(minCol) || !Number.isFinite(maxCol)) {
    // An empty row: no seats to place, so the numbers never get used.
    return { minCol: 0, maxCol: 0, depth: 0 };
  }

  // Linear interpolation from the front row's depth to the back row's.
  const progress = rowCount <= 1 ? 1 : rowPosition / (rowCount - 1);
  const depth = MIN_CURVE_DEPTH_PX + (MAX_CURVE_DEPTH_PX - MIN_CURVE_DEPTH_PX) * progress;

  return { minCol, maxCol, depth };
}

/**
 * The vertical offset for one seat. See the derivation above.
 *
 * Returns a number of pixels to translate downward. Applied as
 * `transform: translateY(...)` rather than as a margin or a grid row, because a
 * transform does not affect layout — the CSS grid keeps its clean rectangular
 * structure (so `gridColumn: colIndex` still works, aisles and all) and the
 * curve is purely a paint-time effect on top of it. That separation is what
 * keeps the whole thing simple.
 */
export function seatOffsetY(colIndex: number, geometry: RowGeometry): number {
  const half = (geometry.maxCol - geometry.minCol) / 2;
  // A row one seat wide has no ends to bow; guard against dividing by zero.
  if (half <= 0) return 0;

  const centre = (geometry.minCol + geometry.maxCol) / 2;
  const t = (colIndex - centre) / half; // −1 at the left end, +1 at the right
  return geometry.depth * t * t; // t² — symmetric, zero in the middle
}

/**
 * The number of grid columns the map needs: the widest row's largest colIndex.
 * Every row is laid out on this same column count so that seat 7 in row A sits
 * directly above seat 7 in row B, even if row B is shorter.
 */
export function computeColumnCount(rows: readonly SeatRow[]): number {
  let maxCol = 0;
  for (const row of rows) {
    for (const seat of row.seats) {
      if (seat.colIndex > maxCol) maxCol = seat.colIndex;
    }
  }
  return maxCol;
}
