---
name: Portal do lojista — fundação (auth no portal + convenções de resposta agregada)
status: audited
references:
  - "management/vision.md#V17"
  - "management/specs/12-api-authentication/requirements.md"
  - "management/specs/13-master-auth/requirements.md"
  - "management/specs/18-security-hardening/requirements.md"
---

# Spec 20 — Portal do lojista: fundação (backend-first)

## O que entrega

Base de API para o **portal do lojista** (frontend futuro). Decisão de produto
(2026-08-04): entregar **backend-first** — as specs 20–25 expõem e documentam
as APIs que cada tela do portal vai consumir, sem frontend nesta fase.

Esta spec 20 é a **fundação**: autenticação do lojista no portal e convenções
de resposta agregada compartilhadas pelas telas (dashboard, carteira, vendas).

1. **Auth do lojista no portal:** hoje o lojista só se autentica por **API key**
   (`X-Api-Key`, Spec 12) — inadequada para um SPA (segredo do cliente). Cria o
   fluxo **login usuário/senha + sessão** para o lojista, no mesmo padrão do
   master (Spec 13: BCrypt, token opaco, expiração, revogação), mas com role
   própria e rotas próprias (`/api/v1/merchant-auth/**`).
2. **Credenciais de acesso iniciais:** no cadastro do lojista
   (`POST /api/v1/merchants`, Spec 12), além da `apiKey`, passa a ser gerada a
   senha inicial do portal — devolvida **uma única vez** na resposta do
   cadastro (mesmo padrão da API key), armazenada apenas como hash BCrypt.
3. **Convenções de resposta agregada:** contrato comum para métricas de topo
   (`MetricCard`), séries temporais (`TimeSeriesPoint`) e paginação
   (`PageResponse` existente) — usadas pelas specs 21–25.
4. **Segurança:** as novas rotas seguem as regras da Spec 18 (autorização por
   recurso via `MerchantAccessGuard`, rate limit no login, 400 robusto).

## Critérios de aceite

1. Migration V13 cria `merchant_credentials` (FK merchant, `password_hash`
   BCrypt, `created_at`, `updated_at`) e registra o **último uso** para
   auditoria; o cadastro de lojista devolve `portalPassword` **uma única vez**.
2. `POST /api/v1/merchant-auth/login` `{username, password}` → token opaco
   (bearer) + `{merchantId, tradingName}`; senha errada → **401**; lojista
   `SUSPENDED`/`INACTIVE` → **403 `merchant_not_active`**.
3. `POST /api/v1/merchant-auth/logout` revoga a sessão (token armazenado como
   hash SHA-256, expira em 8h, mesmo padrão do master).
4. `MerchantPortalAuthFilter` (Spring Security): `Authorization: Bearer
   <token>` → `ROLE_MERCHANT_PORTAL`; rotas `/api/v1/portal/**` exigem essa
   role (401 sem token, 403 com API key de lojista).
5. `GET /api/v1/portal/me` → perfil do lojista logado (`id`, `tradingName`,
   `status`, `walletBalance`) — endpoint de sanidade da sessão.
6. Contrato de métricas documentado (ex.: `MetricCard {label, value, delta,
   period}` e `TimeSeriesPoint {label, value}`) e reutilizado pelas specs 21–25.
7. `mvn test` verde (**~135 testes: 129 + ~6 novos** — `MerchantAuthService`,
   filtro, cadastro com senha inicial) e E2E sem regressão.

## Dependências

- Spec 12 (API key do lojista; `MerchantAccessGuard`).
- Spec 13 (padrão master de login/sessão — reusado com role própria).
- Spec 18 (rate limit, robustez 400, autorização por recurso).
- Spec 19 (lojista `SUSPENDED` não processa; status existente).

## Observações

- **Sem frontend nesta fase** — o contrato REST é o entregável; o SPA fica para
  spec futura.
- **Token do portal ≠ API key:** o portal usa sessão bearer (Spec 13); a API
  key continua sendo o mecanismo para integrações server-to-server (bot).
- **Gift PIX / Jornada do player:** termos de domínio definidos nas specs 23/21
  (decisões confirmadas 2026-08-04).
