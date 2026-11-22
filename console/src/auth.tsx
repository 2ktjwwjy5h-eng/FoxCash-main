// Auth do Master Console (Spec 71): AuthProvider + useAuth + guard de rota
// privada. Token opaco (Spec 13) em sessionStorage via apiClient (chave própria
// 'foxcash.console.token'). Login aceita TOTP opcional (Spec 44 — com MFA ativo
// a senha não basta; 401 uniforme sem oráculo). Logout explícito revoga no
// servidor (POST /auth/master/logout) e limpa o storage.

import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { api, clearToken, getToken, setToken } from './apiClient';

export interface MasterMe {
  masterId: string;
  username: string;
  name: string;
  role: string;
  permissions: string[];
  canManageMasters: boolean;
  mfaActive: boolean;
}

interface AuthContextValue {
  token: string | null;
  me: MasterMe | null;
  login: (username: string, password: string, totpCode?: string) => Promise<MasterMe>;
  logout: () => Promise<void>;
  refreshMe: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setTokenState] = useState<string | null>(() => getToken());
  const [me, setMe] = useState<MasterMe | null>(null);

  const login = useCallback(async (username: string, password: string, totpCode?: string) => {
    const res = await api.post<{ token: string }>(
      '/api/v1/auth/master/login',
      { username, password, totpCode },
      { auth: false },
    );
    setToken(res.token);
    setTokenState(res.token);
    const meRes = await api.get<MasterMe>('/api/v1/master/me');
    setMe(meRes);
    return meRes;
  }, []);

  const logout = useCallback(async () => {
    try {
      await api.post<void>('/api/v1/auth/master/logout');
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
      setMe(await api.get<MasterMe>('/api/v1/master/me'));
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

/** Guard de rota privada — sem token → redirect para /console/login. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { token } = useAuth();
  const location = useLocation();
  if (!token) {
    return <Navigate to="/console/login" state={{ from: location.pathname }} replace />;
  }
  return <>{children}</>;
}
