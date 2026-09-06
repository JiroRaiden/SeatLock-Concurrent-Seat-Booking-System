import { useEffect, useRef, useState } from 'react';
import { formatCountdown } from '@/lib/format';

/**
 * The hold clock.
 *
 * The single most important correctness decision here: the countdown is derived
 * from the server's absolute `expiresAt` timestamp, NOT from a client-side
 * counter that decrements a stored number.
 *
 * Why that matters. A `setInterval` that does `seconds -= 1` drifts, because
 * browsers do not fire timers on time — and, far worse, browsers THROTTLE
 * timers in background tabs to roughly once a minute. A user who switches tabs
 * for three minutes and comes back would see a decrement-based timer showing
 * ~2:40 remaining when the hold expired 40 seconds ago. Recomputing
 * `expiresAt - Date.now()` on every tick means the tab can be frozen for any
 * length of time and the number is still correct the instant it wakes up.
 *
 * The remaining inaccuracy is clock skew between the user's machine and the
 * server, which we accept: the server is the authority, so a skewed client just
 * sees a slightly wrong number and then gets an honest `HOLD_EXPIRED` from the
 * confirm call.
 */
export function useCountdown(expiresAtIso: string | null | undefined): {
  secondsLeft: number;
  expired: boolean;
} {
  const targetMs = expiresAtIso == null ? null : new Date(expiresAtIso).getTime();

  const computeRemaining = (): number => {
    if (targetMs === null || Number.isNaN(targetMs)) return 0;
    return Math.max(0, Math.ceil((targetMs - Date.now()) / 1000));
  };

  const [secondsLeft, setSecondsLeft] = useState(computeRemaining);

  useEffect(() => {
    if (targetMs === null || Number.isNaN(targetMs)) {
      setSecondsLeft(0);
      return;
    }
    // Recompute immediately so a remount (or a new expiry after "extend")
    // does not show a stale value for up to a second.
    setSecondsLeft(Math.max(0, Math.ceil((targetMs - Date.now()) / 1000)));

    const interval = window.setInterval(() => {
      const remaining = Math.max(0, Math.ceil((targetMs - Date.now()) / 1000));
      setSecondsLeft(remaining);
      // Stop the clock at zero. Leaving the interval running would re-render
      // the whole checkout screen once a second forever.
      if (remaining <= 0) window.clearInterval(interval);
    }, 1000);

    return () => {
      window.clearInterval(interval);
    };
  }, [targetMs]);

  return { secondsLeft, expired: secondsLeft <= 0 };
}

/**
 * The visible timer.
 *
 * ACCESSIBILITY: `aria-live="polite"` on a field that changes every second
 * would make a screen reader read the number aloud sixty times a minute, which
 * is unusable. So the live region is a separate, visually hidden element that
 * we only write into at three thresholds — 60s, 30s and 10s. The digits
 * themselves are marked `aria-hidden`, so the visual timer and the announced
 * timer are two different things on purpose.
 */
export function Countdown({
  secondsLeft,
  expired,
}: {
  secondsLeft: number;
  expired: boolean;
}): JSX.Element {
  const urgent = !expired && secondsLeft <= 60;
  const [announcement, setAnnouncement] = useState('');
  const announcedRef = useRef<Set<number>>(new Set());

  useEffect(() => {
    const thresholds = [60, 30, 10];
    for (const threshold of thresholds) {
      // Fire once, when we cross the threshold from above.
      if (secondsLeft <= threshold && !announcedRef.current.has(threshold)) {
        announcedRef.current.add(threshold);
        setAnnouncement(`${String(threshold)} seconds left to complete your booking.`);
        return;
      }
    }
    if (expired && !announcedRef.current.has(0)) {
      announcedRef.current.add(0);
      setAnnouncement('Your seat hold has expired and the seats have been released.');
    }
  }, [secondsLeft, expired]);

  return (
    <div className="flex items-center gap-2">
      <span
        aria-hidden="true"
        className={[
          'display text-[34px] leading-none tabular-nums',
          expired ? 'text-ink-600' : urgent ? 'animate-urgent-pulse text-flare-500' : 'text-fog-50',
        ].join(' ')}
      >
        {formatCountdown(secondsLeft)}
      </span>
      <span className="sr-only" aria-live="polite" role="status">
        {announcement}
      </span>
    </div>
  );
}
