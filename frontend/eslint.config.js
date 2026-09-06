import js from '@eslint/js';
import globals from 'globals';
import tsParser from '@typescript-eslint/parser';
import tsPlugin from '@typescript-eslint/eslint-plugin';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';

/**
 * ESLint flat config (the current format; the old .eslintrc is deprecated).
 *
 * The rule set is deliberately small. TypeScript in strict mode already catches
 * most of what a big lint config is for, so the rules kept here are the ones a
 * type-checker genuinely cannot see — mainly the rules of hooks, which are a
 * runtime contract rather than a type-level one.
 */
export default [
  { ignores: ['dist', 'node_modules', 'eslint.config.js', 'postcss.config.js'] },

  js.configs.recommended,

  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      parser: tsParser,
      ecmaVersion: 2022,
      sourceType: 'module',
      globals: { ...globals.browser, ...globals.es2021 },
      parserOptions: {
        ecmaFeatures: { jsx: true },
      },
    },
    plugins: {
      '@typescript-eslint': tsPlugin,
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...tsPlugin.configs.recommended.rules,

      // The two rules that matter most in a hooks codebase: one catches a hook
      // called conditionally, the other catches a stale closure from a missing
      // dependency. Both are bug classes TypeScript cannot detect.
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'warn',

      // OFF, deliberately. This rule wants a file to export components and
      // nothing else, so that Vite's fast refresh can hot-swap it. But the
      // idiomatic React pattern is to co-locate a hook with the component it
      // belongs to — `useAuth` beside `AuthProvider`, `useCountdown` beside
      // `Countdown`. Splitting cohesive code into extra files to please a
      // dev-server optimisation trades real readability for a marginal
      // improvement in hot-reload behaviour, so we take the reload.
      'react-refresh/only-export-components': 'off',

      // `no-undef` duplicates work the TypeScript compiler already does, and
      // gets it wrong for types. Off, as typescript-eslint itself recommends.
      'no-undef': 'off',

      // OFF because ESLint's core rule does not understand TypeScript function
      // OVERLOADS: `request()` in apiClient.ts declares two signatures plus one
      // implementation, which is valid TypeScript that this rule reads as three
      // redeclarations. TypeScript itself catches genuine redeclarations.
      'no-redeclare': 'off',

      // An unused parameter named `_foo` is usually an intentional placeholder.
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],

      // Empty catch blocks are used on purpose in tokenStore (localStorage can
      // throw) and in apiClient (a non-JSON error body). Each one carries a
      // comment explaining why; this rule would flag all of them.
      'no-empty': ['error', { allowEmptyCatch: true }],
    },
  },
];
