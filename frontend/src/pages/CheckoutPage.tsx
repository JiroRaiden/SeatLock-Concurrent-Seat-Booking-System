import { useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { confirmBooking, extendHold, getBooking } from '@/api/endpoints';
import { Countdown, useCountdown } from '@/components/Countdown';
import { ErrorState, SkeletonBlock } from '@/components/states';
import { isApiError } from '@/lib/apiClient';
import { formatMoney, titleCase } from '@/lib/format';

/**
 * Checkout.
 *
 * The hold is already live by the time this screen mounts — the seat map made
 * it. So this screen's whole job is: show what is reserved, show how long is
 * left, and take a payment before the clock runs out.
 *
 * We load the pending booking with `GET /bookings/{holdId}` rather than passing
 * the hold response through router state. Router state does not survive a
 * refresh or a shared link, and a user who reloads the checkout page must not
 * lose their reservation — the hold is server-side, so the page should be able
 * to rebuild itself from the id in the URL alone.
 */

const PAYMENT_METHODS = [
  { value: 'CARD', label: 'Card', hint: 'Visa, Mastercard, RuPay' },
  { value: 'UPI', label: 'UPI', hint: 'Any UPI app' },
  { value: 'NETBANKING', label: 'Net banking', hint: 'All major banks' },
] as const;

/**
 * The stub provider's tokens, from API.md. A real integration would receive
 * these from the payment gateway's own SDK running in an iframe, and the card
 * number would never touch this origin — that is the entire point of
 * tokenisation, and why the API takes a `paymentToken` and not a PAN.
 */
const DEMO_TOKENS = [
  { value: 'tok_demo_success', label: 'tok_demo_success', hint: 'Approves the payment' },
  { value: 'tok_demo_decline', label: 'tok_demo_decline', hint: 'Declines the payment' },
] as const;

export function CheckoutPage(): JSX.Element {
  const params = useParams<{ holdId: string }>();
  const holdId = params.holdId ?? '';
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const bookingQuery = useQuery({
    queryKey: ['booking', holdId],
    queryFn: () => getBooking(holdId),
    enabled: holdId !== '',
    // Nothing about a pending booking changes except its clock, which we run
    // locally from expiresAt — so there is no reason to poll.
    staleTime: Infinity,
  });

  const [paymentMethod, setPaymentMethod] = useState<string>('CARD');
  const [paymentToken, setPaymentToken] = useState<string>('tok_demo_success');
  const [extended, setExtended] = useState(false);
  /** Local override so the timer updates immediately after a successful extend. */
  const [extendedExpiresAt, setExtendedExpiresAt] = useState<string | null>(null);

  /**
   * ONE idempotency key per checkout attempt, generated once and held in a ref.
   *
   * The ref is load-bearing. If this were `useState` with a fresh value, or a
   * plain `crypto.randomUUID()` inside the mutation function, every retry would
   * carry a NEW key — and a retry with a new key is a brand-new request as far
   * as the server is concerned. The failure that protects against: the network
   * drops after Spring committed the booking but before the response reached
   * us. We retry; with a stable key the server replays the original response
   * and creates nothing; with a fresh key the user is charged twice.
   *
   * `useRef` also survives re-renders without regenerating, which `useMemo`
   * technically does not guarantee.
   */
  const idempotencyKeyRef = useRef<string>(createUuid());

  const expiresAt = extendedExpiresAt ?? bookingQuery.data?.expiresAt ?? null;
  const { secondsLeft, expired } = useCountdown(expiresAt);

  const seatSummary = useMemo(() => {
    const seats = bookingQuery.data?.seats ?? [];
    return {
      seats,
      labels: seats.map((seat) => seat.label).join(', '),
    };
  }, [bookingQuery.data]);

  const extendMutation = useMutation({
    mutationFn: () => extendHold(holdId),
    onSuccess: (result) => {
      setExtendedExpiresAt(result.expiresAt);
      setExtended(true);
      // Keep the cached booking in step so a remount does not show the old time.
      void queryClient.invalidateQueries({ queryKey: ['booking', holdId] });
    },
    onError: () => {
      // The only interesting failure is "already extended / already expired",
      // and in both cases the right thing is to stop offering the button.
      setExtended(true);
    },
  });

  const confirmMutation = useMutation({
    mutationFn: () =>
      confirmBooking(holdId, { paymentMethod, paymentToken }, idempotencyKeyRef.current),
    onSuccess: (booking) => {
      void queryClient.invalidateQueries({ queryKey: ['bookings'] });
      queryClient.setQueryData(['booking', booking.id], booking);
      navigate(`/bookings/${booking.id}`, { replace: true });
    },
  });

  if (holdId === '') {
    return (
      <main className="mx-auto max-w-[1240px] px-4 py-10">
        <ErrorState error={new Error('missing hold')} />
      </main>
    );
  }
  if (bookingQuery.isPending) return <CheckoutSkeleton />;
  if (bookingQuery.isError) {
    return (
      <main className="mx-auto max-w-[900px] px-4 py-10">
        <ErrorState
          error={bookingQuery.error}
          onRetry={() => {
            void bookingQuery.refetch();
          }}
        />
      </main>
    );
  }

  const booking = bookingQuery.data;

  // A booking that is already confirmed should never show a payment form again.
  if (booking.status === 'CONFIRMED') {
    return (
      <main className="mx-auto max-w-[900px] px-4 py-12">
        <div className="surface p-6">
          <p className="label-micro text-mint-500">Already paid</p>
          <h1 className="display mt-1 text-[32px] text-fog-50">This booking is confirmed</h1>
          <Link to={`/bookings/${booking.id}`} className="btn-primary mt-4">
            View ticket
          </Link>
        </div>
      </main>
    );
  }

  const confirmError = confirmMutation.error;
  const holdGone =
    expired ||
    booking.status === 'EXPIRED' ||
    booking.status === 'CANCELLED' ||
    (isApiError(confirmError) && confirmError.code === 'HOLD_EXPIRED');

  return (
    <main className="mx-auto max-w-[1000px] px-4 pb-16 pt-6">
      <h1 className="display text-[32px] text-fog-50">Checkout</h1>
      <p className="mt-1 text-sm text-fog-400">
        Reference <span className="text-fog-200">{booking.reference}</span>
      </p>

      <div className="mt-6 grid gap-6 lg:grid-cols-[minmax(0,1fr)_340px]">
        {/* ------------------------------------------------ payment side -- */}
        <section>
          {/* The clock. Given its own bordered box at the top of the column
              because it is the single most time-critical thing on the page. */}
          <div
            className={`flex items-center justify-between gap-4 rounded-lg border px-4 py-3 ${
              holdGone
                ? 'border-ink-700 bg-ink-850'
                : secondsLeft <= 60
                  ? 'border-flare-500 bg-ink-850'
                  : 'border-ink-700 bg-ink-850'
            }`}
          >
            <div>
              <p className="label-micro">{holdGone ? 'Hold expired' : 'Seats held for'}</p>
              <Countdown secondsLeft={secondsLeft} expired={holdGone} />
            </div>

            {!holdGone && !extended ? (
              <button
                type="button"
                className="btn-ghost"
                disabled={extendMutation.isPending}
                onClick={() => {
                  extendMutation.mutate();
                }}
              >
                {extendMutation.isPending ? 'Extending…' : 'Extend by 3 minutes'}
              </button>
            ) : !holdGone ? (
              // The API grants exactly one extension per hold, so after using it
              // we replace the button with an explanation rather than leaving a
              // dead control on screen.
              <p className="max-w-[180px] text-right text-2xs text-fog-400">
                You have used this booking&rsquo;s one extension.
              </p>
            ) : null}
          </div>

          {holdGone ? (
            /* THE EXPIRY STATE. The seats are genuinely back on sale — the
               Redis key is gone — so there is nothing to salvage and no point
               offering a Pay button that can only fail. The honest thing is to
               say what happened and hand the user a route back. */
            <div className="surface mt-4 p-5">
              <p className="label-micro text-flare-500">Reservation released</p>
              <h2 className="display mt-1 text-[24px] text-fog-50">
                Your hold ran out
              </h2>
              <p className="mt-2 max-w-prose text-sm text-fog-200">
                Seats {seatSummary.labels} were released back to sale when the clock reached
                zero, so they may already belong to someone else. Nothing has been charged.
              </p>
              <Link
                to={`/events/${String(booking.event.id)}/seats`}
                className="btn-primary mt-4"
              >
                Choose seats again
              </Link>
            </div>
          ) : (
            <form
              className="surface mt-4 p-5"
              onSubmit={(event) => {
                event.preventDefault();
                confirmMutation.mutate();
              }}
            >
              <fieldset disabled={confirmMutation.isPending}>
                <legend className="label-micro">Payment method</legend>
                <div className="mt-2 grid gap-2 sm:grid-cols-3">
                  {PAYMENT_METHODS.map((method) => (
                    <label
                      key={method.value}
                      className={`flex cursor-pointer flex-col gap-0.5 rounded border px-3 py-2 transition-colors ${
                        paymentMethod === method.value
                          ? 'border-flare-500 bg-ink-800'
                          : 'border-ink-700 hover:border-ink-600'
                      }`}
                    >
                      <span className="flex items-center gap-2">
                        <input
                          type="radio"
                          name="paymentMethod"
                          value={method.value}
                          checked={paymentMethod === method.value}
                          onChange={(event) => {
                            setPaymentMethod(event.target.value);
                          }}
                          className="accent-flare-500"
                        />
                        <span className="text-sm font-semibold text-fog-50">{method.label}</span>
                      </span>
                      <span className="pl-6 text-2xs text-fog-400">{method.hint}</span>
                    </label>
                  ))}
                </div>

                <div className="mt-5 border-t border-ink-700 pt-4">
                  <p className="label-micro">Demo payment token</p>
                  <p className="mt-1 max-w-prose text-2xs text-fog-400">
                    This project stubs the payment provider. A real integration would receive
                    this token from the gateway&rsquo;s own SDK — card numbers never reach
                    SeatLock&rsquo;s servers.
                  </p>
                  <div className="mt-2 grid gap-2 sm:grid-cols-2">
                    {DEMO_TOKENS.map((token) => (
                      <label
                        key={token.value}
                        className={`flex cursor-pointer items-center gap-2 rounded border px-3 py-2 transition-colors ${
                          paymentToken === token.value
                            ? 'border-flare-500 bg-ink-800'
                            : 'border-ink-700 hover:border-ink-600'
                        }`}
                      >
                        <input
                          type="radio"
                          name="paymentToken"
                          value={token.value}
                          checked={paymentToken === token.value}
                          onChange={(event) => {
                            setPaymentToken(event.target.value);
                          }}
                          className="accent-flare-500"
                        />
                        <span className="min-w-0">
                          <span className="block truncate text-sm text-fog-50">{token.label}</span>
                          <span className="block text-2xs text-fog-400">{token.hint}</span>
                        </span>
                      </label>
                    ))}
                  </div>
                </div>

                {confirmMutation.isError ? (
                  <div className="mt-4">
                    <ErrorState error={confirmMutation.error} compact />
                  </div>
                ) : null}

                <button
                  type="submit"
                  className="btn-primary mt-5 h-10 w-full"
                  disabled={confirmMutation.isPending}
                >
                  {confirmMutation.isPending
                    ? 'Confirming…'
                    : `Pay ${formatMoney(booking.totalMinor)}`}
                </button>
              </fieldset>
            </form>
          )}
        </section>

        {/* -------------------------------------------------- order side -- */}
        <aside className="lg:sticky lg:top-20 lg:self-start">
          <div className="surface p-4">
            <p className="label-micro">Order summary</p>
            <p className="display mt-1 text-[22px] text-fog-50">{booking.event.title}</p>
            <p className="mt-0.5 text-xs text-fog-400">
              {booking.event.venue.name}, {booking.event.venue.city}
            </p>

            <ul className="mt-4 divide-y divide-ink-700 border-y border-ink-700">
              {seatSummary.seats.map((seat) => (
                <li key={seat.id} className="flex items-center justify-between gap-3 py-2">
                  <span className="min-w-0">
                    <span className="text-sm font-semibold text-fog-50">{seat.label}</span>
                    <span className="ml-2 text-2xs uppercase tracking-label text-fog-400">
                      {titleCase(seat.section)}
                    </span>
                  </span>
                  <span className="text-sm text-fog-200">{formatMoney(seat.priceMinor)}</span>
                </li>
              ))}
            </ul>

            <div className="mt-3 flex items-baseline justify-between">
              <span className="label-micro">Total payable</span>
              <span className="display text-[26px] text-flare-500">
                {formatMoney(booking.totalMinor)}
              </span>
            </div>
          </div>
        </aside>
      </div>
    </main>
  );
}

/**
 * `crypto.randomUUID` is unavailable on insecure origins (plain http on a LAN
 * IP, which is exactly how a demo often gets shown to someone). The fallback
 * uses `crypto.getRandomValues`, which IS available there, and only falls back
 * to Math.random as a last resort — an idempotency key needs to be unique, not
 * unguessable, so that is an acceptable floor.
 */
function createUuid(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') {
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    bytes[6] = ((bytes[6] ?? 0) & 0x0f) | 0x40; // version 4
    bytes[8] = ((bytes[8] ?? 0) & 0x3f) | 0x80; // variant 10
    const hex = [...bytes].map((byte) => byte.toString(16).padStart(2, '0')).join('');
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }
  return `fallback-${String(Date.now())}-${Math.random().toString(16).slice(2)}`;
}

function CheckoutSkeleton(): JSX.Element {
  return (
    <main className="mx-auto max-w-[1000px] px-4 pt-6">
      <SkeletonBlock className="h-8 w-40 rounded" />
      <div className="mt-6 grid gap-6 lg:grid-cols-[minmax(0,1fr)_340px]">
        <div>
          <SkeletonBlock className="h-[72px] w-full rounded-lg" />
          <SkeletonBlock className="mt-4 h-[280px] w-full rounded-lg" />
        </div>
        <SkeletonBlock className="h-[240px] w-full rounded-lg" />
      </div>
    </main>
  );
}
