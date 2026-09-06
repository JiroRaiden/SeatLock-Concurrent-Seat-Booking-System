import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { getMe, login as loginRequest, logout as logoutRequest, register as registerRequest } from '@/api/endpoints';
import type { UserSummary } from '@/api/schemas';
import { refreshSession, setSessionExpiredHandler } from '@/lib/apiClient';
import {
  clearTokens,
  getRefreshToken,
  setAccessToken,
  setRefreshToken,
} from '@/lib/tokenStore';

/**
 * Auth state for the React tree.
 *
 * This context holds only the *user* — never the access token. The token lives
 * in `tokenStore`, deliberately outside React, for two reasons:
 *   - the apiClient needs it from plain async functions that have no hooks, and
 *   - putting it in state would mean it ends up in React DevTools' component
 *     tree, which is one more place a token is visible than necessary.
 */

/** `status` drives routing: we must not bounce a user to /login while booting. */
type AuthStatus = 'booting' | 'authenticated' | 'anonymous';

interface AuthContextValue {
  status: AuthStatus;
  user: UserSummary | null;
  login: (email: string, password: string) => Promise<void>;
  register: (email: string, password: string, displayName: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }): JSX.Element {
  const [user, setUser] = useState<UserSummary | null>(null);
  const [status, setStatus] = useState<AuthStatus>('booting');

  /**
   * Boot sequence.
   *
   * The access token is memory-only, so a page reload always starts with no
   * access token even for a logged-in user. If a refresh token survived in
   * localStorage we spend it for a new access token before rendering anything
   * that depends on auth. If there is no refresh token we can skip the network
   * call entirely and settle as anonymous immediately.
   */
  useEffect(() => {
    let cancelled = false;

    async function boot(): Promise<void> {
      if (getRefreshToken() === null) {
        if (!cancelled) setStatus('anonymous');
        return;
      }
      const refreshed = await refreshSession();
      if (cancelled) return;
      if (!refreshed) {
        setStatus('anonymous');
        return;
      }
      try {
        const me = await getMe();
        if (cancelled) return;
        setUser(me);
        setStatus('authenticated');
      } catch {
        if (cancelled) return;
        clearTokens();
        setStatus('anonymous');
      }
    }

    void boot();
    // The cancelled flag stops a late response from setting state after the
    // provider unmounted (React 18 StrictMode mounts effects twice in dev).
    return () => {
      cancelled = true;
    };
  }, []);

  /**
   * The apiClient cannot import this module (that would be a cycle, and it must
   * stay React-free), so it calls back through a registered handler when a
   * refresh fails. This is how a 401 deep inside a query turns into the header
   * showing "Sign in" again.
   */
  useEffect(() => {
    setSessionExpiredHandler(() => {
      setUser(null);
      setStatus('anonymous');
    });
    return () => {
      setSessionExpiredHandler(null);
    };
  }, []);

  const login = useCallback(async (email: string, password: string): Promise<void> => {
    const response = await loginRequest({ email, password });
    setAccessToken(response.accessToken);
    setRefreshToken(response.refreshToken);
    setUser(response.user);
    setStatus('authenticated');
  }, []);

  const register = useCallback(
    async (email: string, password: string, displayName: string): Promise<void> => {
      const response = await registerRequest({ email, password, displayName });
      setAccessToken(response.accessToken);
      setRefreshToken(response.refreshToken);
      setUser(response.user);
      setStatus('authenticated');
    },
    [],
  );

  const logout = useCallback(async (): Promise<void> => {
    const refreshToken = getRefreshToken();
    try {
      if (refreshToken !== null) await logoutRequest(refreshToken);
    } catch {
      // Swallowed on purpose. If the revoke call fails (offline, server down)
      // the user still pressed "Sign out" and must end up signed out locally.
      // The worst case is a refresh token that stays valid server-side until it
      // expires; the alternative — appearing still logged in — is worse.
    } finally {
      clearTokens();
      setUser(null);
      setStatus('anonymous');
    }
  }, []);

  // Memoised so consumers do not re-render on every provider render.
  const value = useMemo<AuthContextValue>(
    () => ({ status, user, login, register, logout }),
    [status, user, login, register, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

/** Throws rather than returning null, so a mis-placed consumer fails loudly. */
export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (context === null) {
    throw new Error('useAuth must be used inside <AuthProvider>');
  }
  return context;
}
