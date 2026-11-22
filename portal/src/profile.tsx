// Perfil de operação do portal (Specs 122/123/139): lista de perfis do lojista +
// perfil ativo, persistido em localStorage (preferência, como idioma). O
// `apiClient` anexa `X-Profile-ID` em toda chamada autenticada (seleção de
// contexto — o backend valida e NUNCA o usa para decidir rota).
//
// Spec 139: o perfil nasce com um **escopo** (GENERAL/BOT_ONLY/GATEWAY_ONLY) e o
// shell ganha uma **lente de contexto** (Bot | Gateway) que filtra os perfis
// exibidos. A lente é estado de UI (localStorage) — não muda modelo/backend; o
// header X-Profile-ID continua saindo do perfil ativo (inalterado).

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api, getActiveProfileId, setActiveProfileId } from './apiClient';
import { useAuth } from './auth';

/** Bot da frota de um perfil (GET /portal/profile — dados leves pro seletor). */
export interface ProfileBot {
  id: string;
  displayName: string;
  platform: 'TELEGRAM' | 'DISCORD';
  status: 'REGISTERING' | 'ACTIVE' | 'PAUSED' | 'ERROR';
}

/** Escopo do perfil (Spec 139 — classificação da operação). */
export type ProfileScope = 'GENERAL' | 'BOT_ONLY' | 'GATEWAY_ONLY';

/** Lente de contexto do shell (Spec 139 — estado de UI, persistido em localStorage). */
export type Lens = 'BOT' | 'GATEWAY';

/** Chave do localStorage da lente (nova — não reusa `foxcash.portal.profile`). */
export const LENS_KEY = 'foxcash.portal.lens';

/** Rótulos amigáveis das rotas de gateway (Spec 139 — exibição; a API devolve chaves). */
export const ROUTE_LABEL: Record<string, string> = {
  PADRAO: 'Padrão (por risco)',
  pagarme: 'Pagar.me',
  asaas: 'Asaas',
  mock: 'Mock (teste)',
  stripe: 'Stripe',
};

/** Rótulo da rota (fallback: chave em caixa alta). */
export function routeLabel(route: string): string {
  if (!route) return ROUTE_LABEL.PADRAO;
  return ROUTE_LABEL[route] ?? route.toUpperCase();
}

/** Rótulo do escopo (Spec 139 — UI). */
export const SCOPE_LABEL: Record<ProfileScope, string> = {
  GENERAL: '⚡ Completo (Bot + Gateway integrados)',
  BOT_ONLY: '🤖 Somente Bot (foco em chat e automações)',
  GATEWAY_ONLY: '🌐 Somente Gateway (foco em páginas web e tráfego)',
};

/** Perfil de operação (Spec 122 — resposta do GET /api/v1/portal/profile). */
export interface OperationProfile {
  id: string;
  name: string;
  scope: ProfileScope; // Spec 139
  gatewayRoute: string; // "PADRAO" | key
  availableRoutes: string[]; // ["PADRAO", ...]
  bots: ProfileBot[];
}

interface ProfilesResponse {
  profiles: OperationProfile[];
  activeProfileId: string | null;
}

interface ProfileContextValue {
  profiles: OperationProfile[];
  loading: boolean;
  error: unknown;
  activeProfileId: string | null;
  /** Perfil ativo (derivado) — null enquanto não há perfil. */
  activeProfile: OperationProfile | null;
  /** Lente de contexto (Spec 139): 'BOT' | 'GATEWAY', persistida em localStorage. */
  lens: Lens;
  /** Troca a lente (grava storage + ajusta o perfil ativo se ficar fora dela). */
  setLens: (l: Lens) => void;
  /** Perfis visíveis na lente atual (derivado): Bot → GENERAL+BOT_ONLY; Gateway → GENERAL+GATEWAY_ONLY. */
  visibleProfiles: OperationProfile[];
  setActiveProfileId: (id: string) => void;
  /** Troca a rota de gateway de um perfil (PATCH — Spec 122). Erro → rethrow (rollback local). */
  setGatewayRoute: (profileId: string, route: string) => Promise<void>;
  /** Cria um perfil (POST {name, scope} — Spec 139, modal limpo). Erro → rethrow. */
  createProfile: (input: { name: string; scope: ProfileScope }) => Promise<void>;
  /** Atribui a frota de bots do perfil (PUT /{id}/bots — Spec 139). Erro → rethrow. */
  assignBots: (profileId: string, botIds: string[]) => Promise<void>;
  refreshProfile: () => Promise<void>;
}

const ProfileContext = createContext<ProfileContextValue | null>(null);

/** Sanidade da lente no load: valor inválido → 'BOT' (primeira superfície de compra). */
function initialLens(): Lens {
  const saved = localStorage.getItem(LENS_KEY);
  return saved === 'GATEWAY' ? 'GATEWAY' : 'BOT';
}

function isVisible(p: OperationProfile, lens: Lens): boolean {
  return lens === 'BOT' ? p.scope !== 'GATEWAY_ONLY' : p.scope !== 'BOT_ONLY';
}

export function ProfileProvider({ children }: { children: ReactNode }) {
  const { token } = useAuth();
  const [profiles, setProfiles] = useState<OperationProfile[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [activeProfileId, setActive] = useState<string | null>(() => getActiveProfileId());
  const [lens, setLensState] = useState<Lens>(initialLens);

  const refreshProfile = useCallback(async () => {
    try {
      const res = await api.get<ProfilesResponse>('/api/v1/portal/profile');
      setProfiles(res.profiles);
      // Sanidade: perfil salvo que não existe mais → cai para o default.
      setActive((current) => {
        const valid = current && res.profiles.some((p) => p.id === current)
          ? current
          : (res.profiles[0]?.id ?? null);
        setActiveProfileId(valid);
        return valid;
      });
    } catch (err) {
      setError(err);
    } finally {
      setLoading(false);
    }
  }, []);

  // Reage ao ciclo de auth: sem token (logout) → limpa o contexto. O CARREGAMENTO
  // dos perfis é disparado no Layout (junto do refreshMe — design D2), evitando
  // GET duplicado no mount (provider + Layout).
  useEffect(() => {
    if (!token) {
      setProfiles([]);
      setLoading(true);
      setError(null);
      setActive(null);
      setActiveProfileId(null);
    }
  }, [token]);

  const setActiveProfileIdLocal = useCallback((id: string) => {
    setActive(id);
    setActiveProfileId(id);
  }, []);

  /** Troca a lente (Spec 139): grava storage + se o perfil ativo ficar fora da
   *  nova lente, cai para o primeiro visível (ou null) e reescreve o perfil salvo. */
  const setLens = useCallback((l: Lens) => {
    setLensState(l);
    localStorage.setItem(LENS_KEY, l);
    setActive((current) => {
      if (!current) return current;
      const stillVisible = profiles.some((p) => p.id === current && isVisible(p, l));
      if (stillVisible) return current;
      const first = profiles.find((p) => isVisible(p, l))?.id ?? null;
      setActiveProfileId(first);
      return first;
    });
  }, [profiles]);

  /** Perfis visíveis na lente atual (derivado, memo). */
  const visibleProfiles = useMemo(
    () => profiles.filter((p) => isVisible(p, lens)),
    [profiles, lens],
  );

  const setGatewayRoute = useCallback(async (profileId: string, route: string) => {
    const updated = await api.patch<OperationProfile>(
      `/api/v1/portal/profile/${profileId}`,
      { gatewayRoute: route },
    );
    // Sucesso → reflete no contexto (a rota persistida vence o estado local).
    setProfiles((prev) => prev.map((p) => (p.id === profileId ? { ...p, gatewayRoute: updated.gatewayRoute } : p)));
  }, []);

  /** Cria um perfil (Spec 139 — modal limpo: nome + Tipo de Operação). */
  const createProfile = useCallback(async ({ name, scope }: { name: string; scope: ProfileScope }) => {
    await api.post<OperationProfile>('/api/v1/portal/profile', { name, scope });
    await refreshProfile();
  }, [refreshProfile]);

  /** Substitui a frota de bots do perfil (Spec 139 — PUT /{id}/bots). */
  const assignBots = useCallback(async (profileId: string, botIds: string[]) => {
    const updated = await api.put<OperationProfile>(`/api/v1/portal/profile/${profileId}/bots`, { botIds });
    setProfiles((prev) => prev.map((p) => (p.id === profileId ? updated : p)));
  }, []);

  const activeProfile = useMemo(
    () => profiles.find((p) => p.id === activeProfileId) || null,
    [profiles, activeProfileId],
  );

  const value = useMemo(
    () => ({
      profiles,
      loading,
      error,
      activeProfileId,
      activeProfile,
      lens,
      setLens,
      visibleProfiles,
      setActiveProfileId: setActiveProfileIdLocal,
      setGatewayRoute,
      createProfile,
      assignBots,
      refreshProfile,
    }),
    [profiles, loading, error, activeProfileId, activeProfile, lens, setLens, visibleProfiles,
      setActiveProfileIdLocal, setGatewayRoute, createProfile, assignBots, refreshProfile],
  );

  return <ProfileContext.Provider value={value}>{children}</ProfileContext.Provider>;
}

export function useProfile(): ProfileContextValue {
  const ctx = useContext(ProfileContext);
  if (!ctx) throw new Error('useProfile deve ser usado dentro de <ProfileProvider>');
  return ctx;
}
