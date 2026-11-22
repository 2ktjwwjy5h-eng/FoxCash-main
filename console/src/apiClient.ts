// Cliente HTTP da SPA do Master Console (Spec 71) — thin client sobre
// /api/v1/master/** + /api/v1/auth/master/**.
//
// - Anexa `Authorization: Bearer <token>` (token opaco do master, Spec 13).
// - Token guardado APENAS em sessionStorage (nunca localStorage) — chave própria
//   do console ('foxcash.console.token').
// - Interceptor 401: sessão expirada/revogada → limpa o storage e redireciona
//   para /console/login (UX do B-A8; 401 uniforme mantido no backend). Rotas
//   públicas de auth NÃO redirecionam (401 lá = credenciais inválidas).
// - 403/409/etc → ApiError propagado, NUNCA logout — o 403 `forbidden` é "sem
//   permissão" (a sessão continua viva; a UI mostra o banner).
// - Erros padronizados do backend (ErrorResponse {code,message}) propagados
//   para a UI. O token NUNCA é logado.

const TOKEN_KEY = 'foxcash.console.token';
const PUBLIC_PATHS = ['/api/v1/auth/master/login'];

export interface ErrorResponse {
  code?: string;
  message?: string;
  fields?: Record<string, string>;
  status?: number;
}

export class ApiError extends Error {
  readonly code?: string;
  readonly status: number;
  readonly fields?: Record<string, string>;
  /** Spec 72: 403 `step_up_required` — ação sensível exige re-prova de posse
   *  (step-up TOTP). O cliente NUNCA desloga nesse caso; a UI abre o prompt e
   *  repete a ação após o POST /master/mfa/step-up. */
  readonly stepUpRequired: boolean;

  constructor(status: number, body: ErrorResponse) {
    super(body?.message || `Erro inesperado (HTTP ${status})`);
    this.name = 'ApiError';
    this.status = status;
    this.code = body?.code;
    this.fields = body?.fields;
    this.stepUpRequired = status === 403 && body?.code === 'step_up_required';
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

/** Logout local (limpa storage) + redireciona para o login do console. */
export function redirectToLogin(): void {
  clearToken();
  if (!window.location.pathname.startsWith('/console/login')) {
    window.location.assign('/console/login');
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
