// Auth do portal (Spec 46): AuthProvider + useAuth + guard de rota privada.
// Token opaco (Spec 20) em sessionStorage via apiClient. Logout explícito
// revoga no servidor (POST /merchant-auth/logout) e limpa o storage.

import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { api, clearToken, getToken, setToken } from './apiClient';

export interface PortalMe {
  merchantId: string;
  name: string;
  email: string;
  walletBalance: string | number;
  /** Spec 94: MFA ativo no portal (alimenta a página Segurança). */
  mfaActive?: boolean;
}

interface AuthContextValue {
  token: string | null;
  me: PortalMe | null;
  login: (email: string, password: string, totpCode?: string, turnstileToken?: string, fingerprintHash?: string) => Promise<PortalMe>;
  logout: () => Promise<void>;
  refreshMe: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setTokenState] = useState<string | null>(() => getToken());
  const [me, setMe] = useState<PortalMe | null>(null);

  const login = useCallback(async (email: string, password: string, totpCode?: string, turnstileToken?: string, fingerprintHash?: string) => {
    // Spec 63 (Fase C): token do Turnstile no body (enviado só quando o widget
    // está ativo — site key configurada). O backend exige apenas com a feature ON.
    // Spec 94: totpCode opcional — exigido apenas quando o lojista tem MFA ativo.
    // Spec 141: fingerprintHash (64 hex) — sinal de risco PASSIVO (best-effort).
    const res = await api.post<{ token: string }>(
      '/api/v1/merchant-auth/login',
      { email, password, totpCode, turnstileToken, fingerprintHash },
      { auth: false },
    );
    setToken(res.token);
    setTokenState(res.token);
    const meRes = await api.get<PortalMe>('/api/v1/portal/me');
    setMe(meRes);
    return meRes;
  }, []);

  const logout = useCallback(async () => {
    try {
      await api.post<void>('/api/v1/merchant-auth/logout');
    } catch {
      /* logout local mesmo se o servidor falhar */
    }
    clearToken();
    setTokenState(null);
    setMe(null);
  }, []);

  const refreshMe = useCallback(async () => {
    if (!getToken()) return;
    try {
      setMe(await api.get<PortalMe>('/api/v1/portal/me'));
    } catch {
      /* 401 já redireciona via interceptor */
    }
  }, []);

  const value = useMemo(
    () => ({ token, me, login, logout, refreshMe }),
    [token, me, login, logout, refreshMe],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth deve ser usado dentro de <AuthProvider>');
  return ctx;
}

/** Guard de rota privada — sem token → redirect para /login preservando o destino. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { token } = useAuth();
  const location = useLocation();
  if (!token) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  }
  return <>{children}</>;
}
