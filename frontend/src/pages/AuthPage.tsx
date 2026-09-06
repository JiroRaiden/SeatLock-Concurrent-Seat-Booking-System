import { useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '@/auth/AuthContext';
import { PosterArt } from '@/components/PosterArt';
import { isApiError } from '@/lib/apiClient';

/**
 * Sign in / Register.
 *
 * One component serves both routes, because the two forms differ by exactly one
 * field. Duplicating the whole screen to add a "name" input would mean two
 * places to fix every future change to error handling or redirect logic.
 *
 * Layout: split screen. Left is a collage of procedurally generated posters —
 * it costs nothing (no image requests), it is on-brand, and it makes the page
 * look like part of a ticketing product. Right is a compact form pinned to the
 * top rather than a small card floating in the middle of an empty page.
 */

/** Titles for the collage. Fixed, so the artwork is identical on every visit. */
const COLLAGE_TITLES = [
  'Meridian',
  'Neon Bazaar',
  'The Long Monsoon',
  'Static Bloom',
  'Paper Tigers',
  'Cassette Hearts',
] as const;

export function AuthPage({ mode }: { mode: 'login' | 'register' }): JSX.Element {
  const { login, register } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  /** Per-field messages from the API's `fieldErrors` map on a 400. */
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  const registering = mode === 'register';

  /**
   * Where to go after signing in. The guard puts the blocked path in router
   * state; falling back to "/" covers a user who came to /login directly.
   * This is what makes "click Proceed → sign in → land back on the seat map"
   * work instead of dumping them on the homepage.
   */
  const redirectTo =
    (location.state as { from?: string } | null)?.from ?? '/';

  async function onSubmit(event: React.FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    setFormError(null);
    setFieldErrors({});
    setSubmitting(true);
    try {
      if (registering) await register(email, password, displayName);
      else await login(email, password);
      // `replace` so Back does not return to a login page the user has already
      // passed through — a small thing that makes the flow feel finished.
      navigate(redirectTo, { replace: true });
    } catch (error) {
      if (isApiError(error)) {
        setFormError(error.message);
        if (error.fieldErrors !== undefined) setFieldErrors(error.fieldErrors);
      } else {
        setFormError('Something went wrong. Please try again.');
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="grid min-h-[calc(100vh-56px)] lg:grid-cols-[1.05fr_minmax(0,420px)]">
      {/* ------------------------------------------------------ collage -- */}
      <section
        aria-hidden="true"
        className="relative hidden overflow-hidden border-r border-ink-700 bg-ink-900 lg:block"
      >
        {/* A slightly rotated, oversized grid bled off all four edges. The
            rotation and the overflow are what stop it reading as a tidy
            three-column feature grid and start it reading as a wall of
            posters. */}
        <div
          className="absolute inset-0 grid grid-cols-3 gap-3 p-6"
          style={{ transform: 'rotate(-6deg) scale(1.28)', transformOrigin: 'center' }}
        >
          {COLLAGE_TITLES.map((title, index) => (
            <div
              key={title}
              className="overflow-hidden rounded-lg border border-ink-700"
              // Alternate columns are nudged vertically so the grid staggers.
              style={{ transform: `translateY(${String((index % 3) * 22 - 22)}px)` }}
            >
              <PosterArt title={title} className="block aspect-[2/3] w-full" />
            </div>
          ))}
        </div>

        {/* Scrim so the collage sits behind the eye rather than competing. */}
        <div
          className="absolute inset-0"
          style={{
            background:
              'linear-gradient(100deg, rgba(8,8,11,0.35) 0%, rgba(8,8,11,0.72) 60%, rgba(8,8,11,0.95) 100%)',
          }}
        />

        <div className="absolute bottom-8 left-8 right-8">
          <p className="label-micro text-flare-500">Seatlock</p>
          <p className="display mt-1 max-w-[16ch] text-[42px] leading-[0.92] text-fog-50">
            Your seat, actually held
          </p>
          <p className="mt-2 max-w-[46ch] text-sm text-fog-400">
            Reservations are locked server-side for eight minutes, so nobody takes your row
            while you are reaching for your card.
          </p>
        </div>
      </section>

      {/* --------------------------------------------------------- form -- */}
      <section className="flex flex-col justify-start px-6 py-10 sm:px-10">
        <h1 className="display text-[36px] text-fog-50">
          {registering ? 'Create an account' : 'Sign in'}
        </h1>
        <p className="mt-1 text-sm text-fog-400">
          {registering
            ? 'One account, all your bookings in one place.'
            : 'Welcome back. Your bookings are waiting.'}
        </p>

        <form
          className="mt-6 flex flex-col gap-4"
          onSubmit={(event) => {
            void onSubmit(event);
          }}
          noValidate
        >
          {registering ? (
            <Field
              id="displayName"
              label="Name"
              value={displayName}
              onChange={setDisplayName}
              autoComplete="name"
              error={fieldErrors['displayName']}
            />
          ) : null}

          <Field
            id="email"
            label="Email"
            type="email"
            value={email}
            onChange={setEmail}
            autoComplete="email"
            error={fieldErrors['email']}
          />

          <Field
            id="password"
            label="Password"
            type="password"
            value={password}
            onChange={setPassword}
            // The correct autocomplete token matters: it tells a password
            // manager whether to offer a saved password or generate a new one.
            autoComplete={registering ? 'new-password' : 'current-password'}
            error={fieldErrors['password']}
            hint={
              registering
                ? 'Between 10 and 128 characters. Length is the requirement — no symbol puzzles.'
                : undefined
            }
          />

          {formError !== null ? (
            // `role="alert"` so a screen reader announces a failed sign-in
            // without the user having to go looking for the message.
            <p role="alert" className="rounded border border-flare-500 bg-ink-850 px-3 py-2 text-sm text-fog-200">
              {formError}
            </p>
          ) : null}

          <button type="submit" className="btn-primary h-10" disabled={submitting}>
            {submitting
              ? registering
                ? 'Creating account…'
                : 'Signing in…'
              : registering
                ? 'Create account'
                : 'Sign in'}
          </button>
        </form>

        <p className="mt-5 text-sm text-fog-400">
          {registering ? 'Already have an account? ' : 'New to SeatLock? '}
          <Link
            to={registering ? '/login' : '/register'}
            // Carry the redirect target across, so switching between the two
            // forms does not lose where the user was originally headed.
            state={location.state}
            className="font-semibold text-flare-500 hover:text-flare-400"
          >
            {registering ? 'Sign in' : 'Create one'}
          </Link>
        </p>
      </section>
    </main>
  );
}

function Field({
  id,
  label,
  value,
  onChange,
  type = 'text',
  autoComplete,
  error,
  hint,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  type?: string;
  autoComplete?: string;
  error?: string | undefined;
  hint?: string | undefined;
}): JSX.Element {
  const describedBy = [
    hint !== undefined ? `${id}-hint` : null,
    error !== undefined ? `${id}-error` : null,
  ]
    .filter((part): part is string => part !== null)
    .join(' ');

  return (
    <div>
      <label htmlFor={id} className="label-micro">
        {label}
      </label>
      <input
        id={id}
        name={id}
        type={type}
        value={value}
        autoComplete={autoComplete}
        onChange={(event) => {
          onChange(event.target.value);
        }}
        // Ties the hint and the error message to the input, so a screen reader
        // reads them as part of the field rather than as loose text nearby.
        aria-describedby={describedBy === '' ? undefined : describedBy}
        aria-invalid={error !== undefined}
        className={`field mt-1.5 ${error !== undefined ? 'border-flare-500' : ''}`}
      />
      {hint !== undefined ? (
        <p id={`${id}-hint`} className="mt-1 text-2xs text-fog-400">
          {hint}
        </p>
      ) : null}
      {error !== undefined ? (
        <p id={`${id}-error`} className="mt-1 text-2xs text-flare-400">
          {error}
        </p>
      ) : null}
    </div>
  );
}
