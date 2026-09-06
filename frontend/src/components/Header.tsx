import { useEffect, useRef, useState } from 'react';
import { Link, NavLink, useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '@/auth/AuthContext';

/**
 * The app chrome: wordmark, city, search, nav, account.
 *
 * Sticky, 1px bottom border, no shadow and no blur — a real ticketing site's
 * header is a solid bar that the content scrolls under, and a hairline is
 * enough to separate two near-black surfaces.
 */

/** Hard-coded because the API exposes no cities endpoint. */
const CITIES = ['Kolkata', 'Mumbai', 'Bengaluru', 'Delhi NCR', 'Hyderabad', 'Pune'] as const;

export function Header(): JSX.Element {
  const { status, user, logout } = useAuth();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();

  // The search box is a controlled input seeded from the URL, so a shared or
  // reloaded link reproduces the same result set.
  const [query, setQuery] = useState(() => searchParams.get('q') ?? '');
  const city = searchParams.get('city') ?? CITIES[0];

  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement | null>(null);

  // Close the account menu on an outside click. Without this the menu stays
  // open while the user interacts with the page behind it.
  useEffect(() => {
    if (!menuOpen) return;
    function onPointerDown(event: MouseEvent): void {
      if (menuRef.current !== null && !menuRef.current.contains(event.target as Node)) {
        setMenuOpen(false);
      }
    }
    document.addEventListener('mousedown', onPointerDown);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
    };
  }, [menuOpen]);

  /**
   * Search commits on submit, not on every keystroke. A debounced
   * search-as-you-type would fire a request per pause and, more importantly,
   * would push a history entry per keystroke if it wrote to the URL. Submit is
   * one request and one history entry.
   */
  function onSearchSubmit(event: React.FormEvent<HTMLFormElement>): void {
    event.preventDefault();
    const next = new URLSearchParams(searchParams);
    if (query.trim() === '') next.delete('q');
    else next.set('q', query.trim());
    setSearchParams(next);
    navigate({ pathname: '/', search: next.toString() });
  }

  function onCityChange(event: React.ChangeEvent<HTMLSelectElement>): void {
    const next = new URLSearchParams(searchParams);
    next.set('city', event.target.value);
    setSearchParams(next);
  }

  return (
    <header className="sticky top-0 z-40 border-b border-ink-700 bg-ink-900">
      <div className="mx-auto flex h-14 max-w-[1240px] items-center gap-4 px-4">
        {/* Wordmark. The dot is the only decorative element in the chrome, and
            it doubles as the accent colour's first appearance on the page. */}
        <Link to="/" className="flex shrink-0 items-baseline gap-1">
          <span className="display text-[26px] tracking-display text-fog-50">Seat</span>
          <span className="display text-[26px] tracking-display text-flare-500">Lock</span>
        </Link>

        <div className="hidden items-center gap-2 border-l border-ink-700 pl-4 md:flex">
          <label htmlFor="city-select" className="label-micro">
            City
          </label>
          <select
            id="city-select"
            value={city}
            onChange={onCityChange}
            className="h-7 rounded border border-ink-700 bg-ink-800 px-2 text-sm text-fog-50"
          >
            {CITIES.map((name) => (
              <option key={name} value={name}>
                {name}
              </option>
            ))}
          </select>
        </div>

        <form onSubmit={onSearchSubmit} className="flex min-w-0 flex-1 items-center" role="search">
          <label htmlFor="site-search" className="sr-only">
            Search events
          </label>
          <input
            id="site-search"
            type="search"
            value={query}
            onChange={(event) => {
              setQuery(event.target.value);
            }}
            placeholder="Search films, concerts, comedy…"
            className="field h-8 max-w-md"
          />
        </form>

        <nav className="hidden items-center gap-1 sm:flex">
          <NavLink
            to="/bookings"
            className={({ isActive }) =>
              `rounded px-2.5 py-1.5 text-sm transition-colors ${
                isActive ? 'text-flare-500' : 'text-fog-200 hover:text-fog-50'
              }`
            }
          >
            Bookings
          </NavLink>
        </nav>

        {status === 'authenticated' && user !== null ? (
          <div className="relative shrink-0" ref={menuRef}>
            <button
              type="button"
              onClick={() => {
                setMenuOpen((open) => !open);
              }}
              aria-expanded={menuOpen}
              aria-haspopup="menu"
              className="flex items-center gap-2 rounded border border-ink-700 bg-ink-800 px-2 py-1 text-sm text-fog-50 transition-colors hover:border-ink-600"
            >
              {/* Initial-in-a-square avatar: no image request, no broken URL,
                  and it identifies the account at a glance. */}
              <span className="flex h-5 w-5 items-center justify-center rounded-sm bg-flare-500 text-2xs font-bold text-white">
                {user.displayName.slice(0, 1).toUpperCase()}
              </span>
              <span className="hidden max-w-[10ch] truncate md:inline">{user.displayName}</span>
            </button>

            {menuOpen ? (
              <div
                role="menu"
                className="absolute right-0 top-full z-50 mt-1 w-44 animate-fade-up rounded border border-ink-700 bg-ink-850 py-1"
              >
                <Link
                  to="/bookings"
                  role="menuitem"
                  className="block px-3 py-2 text-sm text-fog-200 hover:bg-ink-800 hover:text-fog-50"
                  onClick={() => {
                    setMenuOpen(false);
                  }}
                >
                  My bookings
                </Link>
                <button
                  type="button"
                  role="menuitem"
                  className="block w-full px-3 py-2 text-left text-sm text-fog-200 hover:bg-ink-800 hover:text-fog-50"
                  onClick={() => {
                    setMenuOpen(false);
                    void logout();
                  }}
                >
                  Sign out
                </button>
              </div>
            ) : null}
          </div>
        ) : (
          <Link to="/login" className="btn-primary h-8 shrink-0 px-3">
            Sign in
          </Link>
        )}
      </div>
    </header>
  );
}
