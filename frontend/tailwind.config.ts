import type { Config } from 'tailwindcss';

/**
 * Every colour here maps to a CSS custom property declared in src/styles/theme.css.
 *
 * Why the indirection instead of hard-coding hex values in this file?
 * The tokens then exist in exactly one place. Raw SVG attributes, keyframes and
 * inline styles (the seat map and the poster generator use all three) can read
 * `var(--flare-500)` directly, while Tailwind utility classes read the same
 * value through `bg-flare-500`. If the accent colour ever changes, it changes
 * once in theme.css and every surface follows.
 *
 * Note the deliberate absence of Tailwind's default palette: `colors` is
 * REPLACED, not extended, further down. `bg-slate-900` is a compile error's
 * moral equivalent here — the class simply does not exist, so nobody can
 * accidentally reach for a stock colour and break the palette.
 */
const config: Config = {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    // Replacing (not extending) `colors` deletes Tailwind's stock palette.
    colors: {
      transparent: 'transparent',
      current: 'currentColor',
      black: '#000000',
      white: '#ffffff',

      ink: {
        950: 'var(--ink-950)',
        900: 'var(--ink-900)',
        850: 'var(--ink-850)',
        800: 'var(--ink-800)',
        700: 'var(--ink-700)',
        600: 'var(--ink-600)',
      },
      fog: {
        400: 'var(--fog-400)',
        200: 'var(--fog-200)',
        50: 'var(--fog-050)',
      },
      flare: {
        600: 'var(--flare-600)',
        500: 'var(--flare-500)',
        400: 'var(--flare-400)',
      },
      mint: { 500: 'var(--mint-500)' },
      amber: { 500: 'var(--amber-500)' },
      tier: {
        recliner: 'var(--tier-recliner)',
        prime: 'var(--tier-prime)',
        classic: 'var(--tier-classic)',
      },
    },

    extend: {
      fontFamily: {
        // Bebas Neue is a condensed all-caps display face — it is doing the work
        // a cinema marquee does: big, tight, confident. Inter carries every
        // piece of actual information.
        display: ['"Bebas Neue"', 'Impact', 'sans-serif'],
        sans: ['Inter', 'system-ui', '-apple-system', 'sans-serif'],
      },

      fontSize: {
        // A deliberately small ramp. A ticketing product is a dense table of
        // facts, not a landing page, so the body size is 13px and 16px is
        // already a heading.
        '2xs': ['10px', { lineHeight: '14px' }],
        xs: ['11px', { lineHeight: '15px' }],
        sm: ['13px', { lineHeight: '18px' }],
        base: ['14px', { lineHeight: '20px' }],
        lg: ['16px', { lineHeight: '22px' }],
      },

      borderRadius: {
        // Caps the radius scale at 8px. `rounded-3xl` does not exist here, which
        // is the point: soft giant corners are the single strongest "AI landing
        // page" tell, and commercial ticketing UIs use tight corners.
        DEFAULT: '4px',
        sm: '2px',
        md: '4px',
        lg: '8px',
        full: '999px',
      },

      letterSpacing: {
        // 0.08em on 11px uppercase is the label treatment used throughout.
        label: '0.08em',
        display: '0.01em',
      },

      keyframes: {
        // A held seat belongs to someone else *right now* and may free up on its
        // own. A slow pulse says "this is live" without being a distraction.
        holdPulse: {
          '0%, 100%': { opacity: '1' },
          '50%': { opacity: '0.42' },
        },
        // Used to flash seats that were lost to another user in a 409.
        lostFlash: {
          '0%, 100%': { transform: 'scale(1)' },
          '35%': { transform: 'scale(1.22)' },
        },
        // Countdown under 60s.
        urgentPulse: {
          '0%, 100%': { opacity: '1' },
          '50%': { opacity: '0.55' },
        },
        // Skeleton shimmer. A sweep rather than a spinner, because the skeleton
        // is meant to communicate the final layout's shape.
        shimmer: {
          '100%': { transform: 'translateX(100%)' },
        },
        fadeUp: {
          from: { opacity: '0', transform: 'translateY(4px)' },
          to: { opacity: '1', transform: 'translateY(0)' },
        },
      },
      animation: {
        'hold-pulse': 'holdPulse 1.8s ease-in-out infinite',
        'lost-flash': 'lostFlash 0.5s ease-in-out 3',
        'urgent-pulse': 'urgentPulse 1s ease-in-out infinite',
        shimmer: 'shimmer 1.4s infinite',
        'fade-up': 'fadeUp 0.18s ease-out',
      },
    },
  },
  plugins: [],
};

export default config;
