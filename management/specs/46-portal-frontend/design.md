# Spec 46 — Design: Frontend do portal do lojista

## Contexto

Toda a lógica de negócio está pronta na API do portal (Specs 20–25, 31, 32,
35, 40). O frontend é uma **SPA de apresentação** — thin client, sem regra de
negócio duplicada. A stack atual do projeto é Java/Spring no backend e
Playwright (Node) no e2e — a SPA em **React + TypeScript + Vite** segue o
ecossistema Node já presente (`e2e-tests/`), com build estático servido pelo
próprio Spring Boot (recurso estático) para deploy simples na VPS.

## Decisões de arquitetura

### 1. Stack e estrutura

- `portal/` (novo diretório no repo): React 18 + TypeScript + Vite. Build →
  `src/main/resources/static/` do backend (SPA servida pelo mesmo processo —
  sem CORS para o próprio origin; CORS liberado apenas para origins
  configurados `foxcash.portal.cors-allowed-origins` para uso externo).
- Roteamento: react-router (rotas públicas `login`, `forgot-password`,
  `reset-password?token=`; rotas privadas com guard de auth).
- Cliente HTTP: `fetch` wrapper com interceptor (anexa `Authorization: Bearer
  <token>`; resposta 401 → limpa estado + redirect `/login`).

### 2. Storage do token (segurança no cliente)

- **`sessionStorage`** (não `localStorage`): o token sobrevive ao refresh da
  aba mas não a novas abas/sessão do browser — reduz janela de exposição em
  máquinas compartilhadas. O token **não** é escrito em nenhum log/DOM.
- TTL efetivo: 8h (Spec 20) + interceptor 401 (sessão expirada/revogada →
  logout local). Logout explícito chama `POST /merchant-auth/logout`
  (revoga no servidor) e limpa o storage.
- Sem cookies de sessão — nada a configurar de `SameSite`/`Secure` no app;
  quando a SPA for servida sobre HTTPS em prod (nginx/TLS), o tráfego é
  criptografado (requisito de infra já documentado).

### 3. B-A8 — UX de 401 sem oráculo

- Backend **não muda** (401 uniforme). No cliente, o interceptor distingue
  **contexto** (rota atual) para UX: 401 na rota protegida = "sessão
  expirada, faça login"; 401 em `login` = "credenciais inválidas". Nenhuma
  informação de existência de conta é derivada (o backend já responde
  uniforme). Documentar a decisão (fecha o B-A8: 401 uniforme mantido,
  UX tratada no cliente).

### 4. Segurança do build

- Nenhum segredo no bundle (só public keys/URLs); variáveis de ambiente do
  Vite apenas para URL da API (default `/` — mesmo origin).
- Headers já fornecidos pelo backend (CSP `default-src 'none'`) **precisam
  ser adaptados** para servir a SPA: CSP com `default-src 'self'` + `script-src
  'self'` + `style-src 'self' 'unsafe-inline'` (ou hash) + `img-src 'self' data:`
  + `connect-src 'self'` — mudança no `SecurityConfig` quando a SPA for
  servida (controlada por config `foxcash.portal.serve-frontend=true`).
  Enquanto não servida, a CSP restrita atual permanece.
- Sanitização: valores da API renderizados como texto (React escapa por
  padrão) — sem `dangerouslySetInnerHTML`.

### 5. E2E browser

- `e2e-tests/` ganha um spec browser (`portal-ui.spec.js`) com Playwright
  chromium: login → dashboard (métricas) → cobrança avulsa (QR exibido) →
  destinos (máscara no DOM, nunca valor em claro) → logout → 401/redirect.
  Mantém o `request`-based existente (API) para o restante da suíte.

## Arquivos (visão geral)

- `portal/` (SPA React/Vite/TS: rotas, páginas, `apiClient.ts` com
  interceptor, componentes)
- `src/main/resources/static/` (build servido) + `SecurityConfig` (CSP para
  a SPA, condicional) + `PortalProperties.cors-allowed-origins`/`serve-frontend`
- `e2e-tests/tests/portal-ui.spec.js` (browser)
- README (como buildar/rodar o portal)
