import type { ReactNode } from 'react';
import { isApiError } from '@/lib/apiClient';

/**
 * The three non-happy states, in one file because they are always considered
 * together: if a screen handles loading it must also handle empty and error.
 *
 * Skeletons here deliberately mirror the SHAPE of the content they replace —
 * a poster-sized block where a poster will be, two text bars where the title
 * and metadata will be. A centred spinner tells the user "something is
 * happening"; a shaped skeleton tells them "a rail of posters is arriving",
 * and the layout does not jump when it does. That absence of reflow is the
 * whole reason to prefer skeletons.
 */

/** One shimmering block. `rounded` matches the surface it stands in for. */
export function SkeletonBlock({
  className = '',
  style,
}: {
  className?: string;
  /** Used by the seat-map skeleton to apply the same auditorium curve. */
  style?: React.CSSProperties;
}): JSX.Element {
  return (
    <div className={`relative overflow-hidden bg-ink-800 ${className}`} style={style}>
      {/* The sweep is a translated gradient rather than an opacity pulse, which
          reads as "loading" instead of "broken". */}
      <div
        className="absolute inset-0 -translate-x-full animate-shimmer"
        style={{
          background:
            'linear-gradient(90deg, transparent 0%, rgba(255,255,255,0.045) 50%, transparent 100%)',
        }}
      />
    </div>
  );
}

/** Skeleton for one poster card on a rail. Matches EventCard's dimensions. */
export function PosterCardSkeleton(): JSX.Element {
  return (
    <div className="w-[168px] shrink-0">
      <SkeletonBlock className="aspect-[2/3] w-full rounded-lg" />
      <SkeletonBlock className="mt-2 h-3 w-4/5 rounded" />
      <SkeletonBlock className="mt-1.5 h-2.5 w-3/5 rounded" />
    </div>
  );
}

export function RailSkeleton({ count = 6 }: { count?: number }): JSX.Element {
  return (
    <div className="flex gap-3 overflow-hidden">
      {Array.from({ length: count }, (_, index) => (
        <PosterCardSkeleton key={index} />
      ))}
    </div>
  );
}

/**
 * Error state with a retry.
 *
 * `message` comes from the API envelope, which API.md guarantees is safe to
 * show a user — it never carries a stack trace or a SQL fragment. Anything that
 * is not an ApiError gets a generic line, because an unexpected exception's
 * message is not something we want to put in front of a user.
 */
export function ErrorState({
  error,
  onRetry,
  compact = false,
}: {
  error: unknown;
  onRetry?: (() => void) | undefined;
  compact?: boolean;
}): JSX.Element {
  const message = isApiError(error)
    ? error.message
    : 'Something went wrong on our side. Please try again.';
  const traceId = isApiError(error) ? error.traceId : undefined;

  return (
    <div
      role="alert"
      className={`surface flex flex-col items-start gap-3 ${compact ? 'p-4' : 'p-6'}`}
    >
      <div className="flex items-center gap-2">
        {/* A drawn glyph, not an emoji: emoji render differently on every
            platform and read as informal in a commercial product. */}
        <span
          aria-hidden="true"
          className="flex h-5 w-5 items-center justify-center rounded-full border border-flare-500 text-2xs font-bold text-flare-500"
        >
          !
        </span>
        <p className="label-micro text-flare-500">Request failed</p>
      </div>
      <p className="max-w-prose text-sm text-fog-200">{message}</p>
      {traceId !== undefined ? (
        <p className="text-2xs text-fog-400">
          Trace <span className="text-fog-200">{traceId}</span>
        </p>
      ) : null}
      {onRetry !== undefined ? (
        <button type="button" className="btn-ghost" onClick={onRetry}>
          Try again
        </button>
      ) : null}
    </div>
  );
}

export function EmptyState({
  title,
  hint,
  action,
}: {
  title: string;
  hint?: string;
  action?: ReactNode;
}): JSX.Element {
  return (
    <div className="surface flex flex-col items-start gap-2 p-6">
      <p className="display text-lg text-fog-50">{title}</p>
      {hint !== undefined ? <p className="max-w-prose text-sm text-fog-400">{hint}</p> : null}
      {action}
    </div>
  );
}
