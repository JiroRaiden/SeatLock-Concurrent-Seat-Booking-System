import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    // `@/x` instead of `../../../x`. Mirrored in tsconfig.app.json paths so the
    // type-checker and the bundler agree on what `@` means.
    alias: { '@': path.resolve(__dirname, './src') },
  },
  server: {
    port: 5173,
    proxy: {
      // In dev the app calls same-origin `/api/...`, and Vite forwards to Spring
      // on :8080. This is not cosmetic — it keeps the browser from ever making a
      // cross-origin request during development, so CORS preflights and
      // SameSite cookie rules behave the same locally as they do in production
      // behind nginx. Fewer "works on my machine" surprises.
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    // Source maps make the demo debuggable in devtools without shipping source.
    sourcemap: true,
  },
});
