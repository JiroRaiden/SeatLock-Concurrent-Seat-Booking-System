import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from '@/App';
import { AuthProvider } from '@/auth/AuthContext';
import { isApiError } from '@/lib/apiClient';
import '@/styles/index.css';

/**
 * One QueryClient for the whole app, created at module scope so that a hot
 * reload of a component never blows away the cache.
 */
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      /**
       * Retrying a 4xx is pointless and sometimes harmful: a 404 will never
       * become a 200, a 401 is handled by the refresh logic in the apiClient,
       * and retrying a 429 makes the rate limit worse. So we only retry
       * server-side and network failures, and only twice.
       */
      retry: (failureCount, error) => {
        if (isApiError(error)) {
          if (error.status >= 400 && error.status < 500) return false;
        }
        return failureCount < 2;
      },
      // 30s covers the "user clicks into an event and straight back" case
      // without serving obviously stale seat counts.
      staleTime: 30_000,
      refetchOnWindowFocus: false,
    },
    mutations: {
      // Never auto-retry a write. `POST /holds` is not idempotent, and confirm
      // is only safe to replay with the SAME Idempotency-Key — which the
      // checkout screen manages deliberately. Automatic retries here would
      // undermine both.
      retry: false,
    },
  },
});

const rootElement = document.getElementById('root');
if (rootElement === null) {
  throw new Error('Root element #root is missing from index.html');
}

createRoot(rootElement).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        {/* AuthProvider sits inside the router because it navigates, and inside
            the query provider because logging out invalidates cached data. */}
        <AuthProvider>
          <App />
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
);
