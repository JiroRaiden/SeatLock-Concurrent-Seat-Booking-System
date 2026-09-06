import { Link, Route, Routes, useLocation } from 'react-router-dom';
import { useEffect } from 'react';
import { Header } from '@/components/Header';
import { RequireAuth } from '@/auth/RequireAuth';
import { BookingDetailPage } from '@/pages/BookingDetailPage';
import { BookingsPage } from '@/pages/BookingsPage';
import { BrowsePage } from '@/pages/BrowsePage';
import { CheckoutPage } from '@/pages/CheckoutPage';
import { EventDetailPage } from '@/pages/EventDetailPage';
import { AuthPage } from '@/pages/AuthPage';
import { SeatMapPage } from '@/pages/SeatMapPage';

export function App(): JSX.Element {
  return (
    <div className="min-h-screen bg-ink-950">
      <ScrollToTop />
      <Header />

      <Routes>
        <Route path="/" element={<BrowsePage />} />
        <Route path="/events/:id" element={<EventDetailPage />} />
        {/* The seat map itself is public: a user can browse and select seats
            without an account, and is only asked to sign in at Proceed, when
            they actually need an identity to own the hold. Forcing a login
            before the map is what makes ticketing sites feel hostile. */}
        <Route path="/events/:id/seats" element={<SeatMapPage />} />

        <Route path="/login" element={<AuthPage mode="login" />} />
        <Route path="/register" element={<AuthPage mode="register" />} />

        <Route
          path="/checkout/:holdId"
          element={
            <RequireAuth>
              <CheckoutPage />
            </RequireAuth>
          }
        />
        <Route
          path="/bookings"
          element={
            <RequireAuth>
              <BookingsPage />
            </RequireAuth>
          }
        />
        <Route
          path="/bookings/:id"
          element={
            <RequireAuth>
              <BookingDetailPage />
            </RequireAuth>
          }
        />

        <Route path="*" element={<NotFoundPage />} />
      </Routes>

      <Footer />
    </div>
  );
}

/**
 * React Router preserves scroll position across navigations, which is right for
 * a Back press and wrong for a forward one: clicking an event on a rail you had
 * scrolled halfway down would drop you halfway down the detail page. Resetting
 * on every pathname change is the simple, predictable behaviour.
 */
function ScrollToTop(): null {
  const { pathname } = useLocation();
  useEffect(() => {
    window.scrollTo(0, 0);
  }, [pathname]);
  return null;
}

function NotFoundPage(): JSX.Element {
  return (
    <main className="mx-auto max-w-[1240px] px-4 py-20">
      <p className="label-micro text-flare-500">404</p>
      <h1 className="display mt-1 text-[44px] text-fog-50">No such page</h1>
      <p className="mt-2 max-w-prose text-sm text-fog-400">
        The link may be old, or the show it pointed at may have finished its run.
      </p>
      <Link to="/" className="btn-primary mt-5">
        Back to browse
      </Link>
    </main>
  );
}

function Footer(): JSX.Element {
  return (
    <footer className="mt-12 border-t border-ink-700 bg-ink-900">
      <div className="mx-auto flex max-w-[1240px] flex-wrap items-center justify-between gap-3 px-4 py-5">
        <p className="text-2xs text-fog-400">
          SeatLock — a concurrent seat-booking demo. No real payments are processed.
        </p>
        <p className="text-2xs text-fog-400">Prices in INR, inclusive of taxes.</p>
      </div>
    </footer>
  );
}
