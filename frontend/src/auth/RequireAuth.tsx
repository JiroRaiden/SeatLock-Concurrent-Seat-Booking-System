import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '@/auth/AuthContext';

/**
 * Route guard for the authenticated screens (checkout, bookings).
 *
 * The `booting` branch matters: on a hard refresh we do not yet know whether
 * the stored refresh token is good. Redirecting during that window would throw
 * a logged-in user to /login on every reload, which is the classic bug in
 * hand-rolled auth guards.
 *
 * This is a UX guard, not a security boundary. It hides screens; it does not
 * protect data. Every protected endpoint is enforced server-side, and that is
 * where the real check lives — anyone can edit client state in devtools.
 */
export function RequireAuth({ children }: { children: ReactNode }): JSX.Element {
  const { status } = useAuth();
  const location = useLocation();

  if (status === 'booting') {
    return (
      <div className="mx-auto max-w-5xl px-4 py-16">
        <div className="h-4 w-40 animate-pulse rounded bg-ink-800" />
      </div>
    );
  }

  if (status === 'anonymous') {
    // `state.from` lets the login screen send the user back to where they were
    // heading, which matters most for a checkout link with a live hold clock.
    // `replace` keeps the guarded URL out of history, so Back does not bounce.
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  }

  return <>{children}</>;
}
