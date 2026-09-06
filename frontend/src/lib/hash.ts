/**
 * A tiny, stable string hash.
 *
 * This is the seed for the whole procedural poster system, so it has exactly
 * two requirements, and cryptographic strength is not one of them:
 *
 *   1. DETERMINISTIC — "Meridian" must produce the same integer on every
 *      machine, in every browser, forever. A poster that changed on reload
 *      would look like a bug, and the same film must look the same on the
 *      Browse rail and on its detail page.
 *   2. WELL-SPREAD — two similar titles ("Meridian" / "Meridian II") should
 *      land far apart, so an events list does not come out as six posters in
 *      the same colourway.
 *
 * This is FNV-1a, chosen because it is about six lines long and I can explain
 * every one of them. It walks the bytes of the string; for each byte it XORs
 * the byte into the accumulator and then multiplies by a fixed prime. XOR mixes
 * the new data in, and the multiply by a large prime carries that change
 * upward into the high bits, so a one-character difference cascades through the
 * whole value (avalanche).
 *
 * `Math.imul` is doing real work: JavaScript numbers are 64-bit floats, so a
 * plain `h * 16777619` would exceed 2^53 and silently lose precision, making
 * the hash non-deterministic across values. `Math.imul` performs a true 32-bit
 * integer multiply with wraparound, which is exactly the C semantics FNV
 * assumes. `>>> 0` at the end coerces the signed 32-bit result to unsigned so
 * we never hand back a negative seed.
 */
export function hashString(input: string): number {
  let hash = 0x811c9dc5; // FNV offset basis (32-bit)
  for (let index = 0; index < input.length; index += 1) {
    hash ^= input.charCodeAt(index);
    hash = Math.imul(hash, 0x01000193); // FNV prime (32-bit)
  }
  return hash >>> 0;
}

/**
 * A deterministic pseudo-random sequence derived from one seed.
 *
 * The poster generator needs several independent-looking numbers per poster
 * (which treatment, which palette, rotation, band offsets, dot jitter). Calling
 * `hashString` repeatedly on the same title would return the same number every
 * time, and `Math.random()` would break determinism — so we need a seeded
 * generator that advances.
 *
 * This is mulberry32: one 32-bit state, advanced by a fixed increment, then
 * scrambled by two rounds of multiply-shift-XOR. It is a well-known small PRNG
 * with good enough distribution for visual work and, again, is short enough to
 * read and explain in an interview.
 */
export function createRandom(seed: number): () => number {
  let state = seed >>> 0;
  return function next(): number {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296; // → [0, 1)
  };
}

/** Picks a stable element from a list. Returns undefined only for an empty list. */
export function pickFrom<T>(items: readonly T[], seed: number): T | undefined {
  if (items.length === 0) return undefined;
  return items[seed % items.length];
}
