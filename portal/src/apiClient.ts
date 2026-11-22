// Cliente HTTP da SPA do portal (Spec 46) — thin client sobre /api/v1/portal/**.
//
// - Anexa `Authorization: Bearer <token>` (token opaco da Spec 20).
// - Token guardado APENAS em sessionStorage (nunca localStorage) — sobrevive ao
//   refresh da aba, some em nova aba/sessão (máquina compartilhada).
// - Interceptor 401: sessão expirada/revogada → limpa o storage e redireciona
//   para /login (B-A8: 401 uniforme mantido no backend; UX tratada no cliente).
// - Erros padronizados do backend (ErrorResponse {code,message}) propagados
//   para a UI. O token NUNCA é logado.
//
// Em produção a SPA é servida pelo próprio Spring Boot (mesmo origin) — base
// relativa ('/'). Em dev o Vite faz proxy para o backend (vite.config.ts).

const TOKEN_KEY = 'foxcash.portal.token';
const PUBLIC_PATHS = ['/api/v1/merchant-auth/login', '/api/v1/merchant-auth/forgot-password', '/api/v1/merchant-auth/reset-password'];

/**
 * Perfil de operação ativo (Spec 122/123) — preferência persistente do lojista
 * (como idioma/moeda), guardada em localStorage: `{ activeProfileId }`. O
 * header `X-Profile-ID` é **seleção de contexto** — o backend valida a
 * pertinência e NUNCA o usa para decidir rota (fonte da verdade é o banco).
 */
export const PROFILE_KEY = 'foxcash.portal.profile';

/** Id do perfil ativo salvo no localStorage (ou null). */
export function getActiveProfileId(): string | null {
  const saved = localStorage.getItem(PROFILE_KEY);
  if (!saved) return null;
  try {
    return JSON.parse(saved)?.activeProfileId ?? null;
  } catch {
    return null;
  }
}

/** Persiste o perfil ativo (sanidade do valor é feita pelo ProfileProvider). */
export function setActiveProfileId(id: string | null): void {
  if (id === null) {
    localStorage.removeItem(PROFILE_KEY);
  } else {
    localStorage.setItem(PROFILE_KEY, JSON.stringify({ activeProfileId: id }));
  }
}

export interface ErrorResponse {
  code?: string;
  message?: string;
  /** Campos inválidos (validação 400) — chave → mensagem. */
  fields?: Record<string, string>;
  status?: number;
}

export class ApiError extends Error {
  readonly code?: string;
  readonly status: number;
  readonly fields?: Record<string, string>;

  constructor(status: number, body: ErrorResponse) {
    super(body?.message || `Erro inesperado (HTTP ${status})`);
    this.name = 'ApiError';
    this.status = status;
    this.code = body?.code;
    this.fields = body?.fields;
  }
}

export function getToken(): string | null {
  return sessionStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string): void {
  sessionStorage.setItem(TOKEN_KEY, token);
}

export function clearToken(): void {
  sessionStorage.removeItem(TOKEN_KEY);
}

export function isAuthed(): boolean {
  return getToken() !== null;
}

/** Logout local (limpa storage) + redireciona para o login. */
export function redirectToLogin(): void {
  clearToken();
  if (!window.location.pathname.startsWith('/login')) {
    window.location.assign('/login');
  }
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  headers?: Record<string, string>;
  /** true (default) anexa o Bearer do sessionStorage. */
  auth?: boolean;
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, headers = {}, auth = true } = options;

  const requestHeaders: Record<string, string> = { ...headers };
  if (body !== undefined) {
    requestHeaders['Content-Type'] = 'application/json';
  }
  if (auth) {
    const token = getToken();
    if (token) {
      requestHeaders['Authorization'] = `Bearer ${token}`;
    }
    // Spec 122/123: seleção de contexto do perfil ativo (hint documentado — o
    // backend valida a pertinência e NUNCA o usa para decidir rota de cobrança).
    const profileId = getActiveProfileId();
    if (profileId) {
      requestHeaders['X-Profile-ID'] = profileId;
    }
  }

  const response = await fetch(path, {
    method,
    headers: requestHeaders,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });

  if (response.status === 401) {
    // Sessão expirada/revogada — limpa e volta ao login. Rotas públicas de auth
    // NÃO redirecionam (401 lá = credenciais inválidas, tratado na página).
    const isPublicAuth = PUBLIC_PATHS.some((p) => path.startsWith(p));
    if (!isPublicAuth) {
      redirectToLogin();
    }
    let errBody: ErrorResponse = {};
    try {
      errBody = await response.json();
    } catch {
      /* corpo vazio */
    }
    throw new ApiError(401, errBody);
  }

  const contentType = response.headers.get('content-type') || '';
  const raw = contentType.includes('application/json') ? await response.json() : null;

  if (!response.ok) {
    throw new ApiError(response.status, (raw as ErrorResponse) || {});
  }
  return raw as T;
}

export const api = {
  get: <T>(path: string, options?: Partial<RequestOptions>) => request<T>(path, { method: 'GET', ...options }),
  post: <T>(path: string, body?: unknown, options?: Partial<RequestOptions>) =>
    request<T>(path, { method: 'POST', body, ...options }),
  put: <T>(path: string, body?: unknown, options?: Partial<RequestOptions>) =>
    request<T>(path, { method: 'PUT', body, ...options }),
  patch: <T>(path: string, body?: unknown, options?: Partial<RequestOptions>) =>
    request<T>(path, { method: 'PATCH', body, ...options }),
  del: <T>(path: string, options?: Partial<RequestOptions>) => request<T>(path, { method: 'DELETE', ...options }),
};
