# Spec 46 — Task breakdown

Status: `audited` (2026-08-08 — ciclo SDD fechado).

## Implementação

- [x] Scaffold `portal/` (React 18 + TS + Vite) + build copiado para
      `src/main/resources/static/`.
- [x] `apiClient.ts` (fetch wrapper: Bearer header, interceptor 401 → logout
      local + redirect `/login`, tratamento de `ErrorResponse`).
- [x] Auth: rotas públicas (`login`, `forgot-password`,
      `reset-password?token=`) + guard de rota privada + `sessionStorage`.
- [x] Páginas: dashboard (Spec 21), carteira (22), cobrança avulsa + Gift
      (23), vendas (25), destinos mascarados (31), saque (32/36), webhooks
      (35), credenciais (24).
- [x] `SecurityConfig`: CSP para servir a SPA (condicional
      `foxcash.portal.serve-frontend=true`) + `PortalProperties`
      (`cors-allowed-origins`).
- [x] E2E browser `portal-ui.spec.js`: login → dashboard → cobrança (QR) →
      venda paga (webhook PAID) → destinos (máscara, sem valor em claro no
      DOM) → saque com destino → logout → 401/redirect.
- [x] README (build/run do portal — seção "Portal do lojista (SPA — Spec 46)").

## Verificação

- [x] `npm run build` no `portal/` gera o estático; backend serve a SPA.
- [x] `mvn test` verde (**1101/0/0** — CSP condicional default off mantém os
      testes atuais); E2E **105/105** (suite completa inclui o novo
      `portal-ui` browser ampliado).
- [x] Smoke: login via browser, sessão expirada → redirect; token só em
      `sessionStorage` (nunca `localStorage`).
