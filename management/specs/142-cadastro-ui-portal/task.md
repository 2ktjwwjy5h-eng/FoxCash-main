---
name: Tela de cadastro do lojista no portal (Spec 39 UI)
status: audited
---

# Spec 142 — Task: Tela de cadastro do lojista no portal

Ordem: T0 (página/estrutura) → T1 (integração backend) → T2 (termos/UX) →
T3 (rota pública + fix) → T4 (E2E/build).

## T0 — Página e estrutura (critérios 1, 5)

- [ ] T0.1: `portal/src/pages/RegisterPage.tsx` — estados (D2) e layout
      `auth-card` com `Field` para Nome (maxLength 120), E-mail (type=email,
      maxLength 255, autoComplete=username) e Senha (minLength 8, maxLength 72,
      autoComplete=new-password).
- [ ] T0.2: Rota pública `/register` no `App.tsx` + link "Criar conta" no
      `auth-links` do `LoginPage`.

## T1 — Integração backend (critério 3)

- [ ] T1.1: `handleSubmit` → `api.post('/api/v1/merchants', {name, email,
      password, turnstileToken?, fingerprintHash?}, {auth:false})` (Spec 46);
      `getFingerprintHash()` (Spec 141) com best-effort.
- [ ] T1.2: Turnstile (Spec 63) — `useEffect` em `/api/v1/portal/turnstile-config`;
      com `siteKey` → `loadTurnstileScript` + widget invisível (`callback →
      setTurnstileToken`); sem `siteKey` → widget off.

## T2 — Termos e estados de UI (critérios 2, 4, 7)

- [ ] T2.1: Checkbox de termos (D3) com link para `docs/politicas-uso.md`; botão
      **Criar conta** com `disabled={submitting || !acceptedTerms}`.
- [ ] T2.2: `ErrorBanner` para 409/400/403/erros de rede; `submitting` desabilita
      o botão ("Criando conta…").
- [ ] T2.3: Tela de sucesso — chave `.key-value`, **Copiar chave** (Spec 73,
      feedback "Copiada ✓"), aviso de exibição única, botão **Fazer login** →
      `/login`.

## T3 — Rota pública da SPA + fix (critério 6)

- [ ] T3.1: `"/register"` em `SecurityConfig.spaPublicPaths()` e
      `PortalSpaController.forwardToIndex()` (sem o fix, `GET /register` direto
      → 401 JSON).

## T4 — Testes e build (critério 8)

- [ ] T4.1: `e2e-tests/tests/register-ui.spec.js` — 2 cenários (cadastro completo
      com API key 1x; link "Criar conta" no login) + import do `@playwright/test`.
- [ ] T4.2: `tsc -b` (portal) + `mvn package`; verificação ao vivo `GET /register`
      → 200 HTML com `serve-frontend=true`.

---

## Implementação (2026-08-17) — notas

Implementação concluída ANTES da formalização da spec (a tela foi feita na
sessão e o fluxo SDD documenta o entregue). Notas de desvio/execução:

- **T0.1/T1.1/T2.x entregues no `RegisterPage.tsx`** — exatamente o desenhado em
  D2/D3/D4: `Field`s com os limites da Spec 39, checkbox de termos com link,
  submit com `{auth:false}` + turnstile + fingerprint, tela de sucesso com a
  API key 1x (`copyToClipboard` + "Copiada ✓" + "Fazer login").
- **T3.1 (fix)** — `/register` nas duas listas (`SecurityConfig.spaPublicPaths`
  e `PortalSpaController`); sem isso o `page.goto('/register')` do E2E (cenário
  1) recebia 401 e o deep-link/F5 quebrava.
- **T4.1 (E2E)** — 2 cenários em `register-ui.spec.js`. **Achado corrigido na
  execução**: o spec usava `test` sem `const { test } = require('@playwright/test')`
  → `ReferenceError: test is not defined` (nem rodava, nem rodaria no CI);
  import adicionado.
- **T4.2** — `tsc -b` limpo; `mvn package -DskipTests` OK; verificação ao vivo
  com a app em dev (`FOXCASH_PORTAL_SERVE_FRONTEND=true`, banco `foxcash` local):
  `GET /register` → **200 text/html** (SPA) e E2E **2/2 passaram**.
- **Build do estático** — `static/index.html` → novo asset `index-B30SWor6.js`;
  o `emptyOutDir: true` do build do portal apaga `static/console/` (README) →
  **console reconstruído por último** (ficou idêntico ao HEAD, sem regressão).

**Âncoras validadas na spec:** `CreateMerchantRequest`/`MerchantRegisteredResponse`
(Spec 39); `api.post` com `{auth:false}` (Spec 46); `getFingerprintHash`
(`fingerprint.ts`, Spec 141); `/api/v1/portal/turnstile-config` (Spec 63);
`spaPublicPaths`/`PortalSpaController` (Spec 46).

## Auditoria (2026-08-17) — 8/8 critérios OK, 0 achados abertos

Verificação 1:1 contra o código real (critérios do requirements):

- **C1 (formulário)** — `RegisterPage.tsx`: `Field` Nome (`maxLength=120`),
  E-mail (`type=email`, `maxLength=255`, `autoComplete=username`) e Senha
  (`minLength=8`, `maxLength=72`, `autoComplete=new-password`) — conferido no
  fonte e no E2E (`getByLabel`).
- **C2 (termos)** — checkbox "Li e concordo com os termos de uso e condições"
  com link `TERMS_URL` (`docs/politicas-uso.md`, `target=_blank rel=noopener`);
  botão **Criar conta** `disabled={submitting || !acceptedTerms}` (linha 203) +
  re-validação defensiva no handler (linha 88).
- **C3 (submit)** — `api.post('/api/v1/merchants', {name, email, password,
  turnstileToken?, fingerprintHash?}, {auth:false})` (linha 105) + `getFingerprintHash()`
  (Spec 141) best-effort; `submitting` desabilita o botão ("Criando conta…");
  409/400 → `ApiError` → `ErrorBanner`.
- **C4 (sucesso API key 1x)** — tela "Conta criada — guarde sua chave de API"
  com `.key-value`, `copyToClipboard` + "Copiada ✓" (linha 116) e **Fazer
  login** → `navigate('/login')` (linha 139); aviso de exibição única.
- **C5 (rota e entrada)** — `/register` no `App.tsx` (rota pública) + link
  "Criar conta" no `auth-links` do `LoginPage` — conferido no diff do commit
  `5c18e4f`.
- **C6 (rota pública da SPA — fix)** — `/register` em
  `SecurityConfig.spaPublicPaths()` (linha 280) e
  `PortalSpaController.forwardToIndex()` (linha 25); **verificação ao vivo**:
  app em dev com `serve-frontend=true` → `GET /register` = **200 text/html**
  (SPA), `GET /login` = 200 (controle).
- **C7 (a11y/UX)** — labels via `Field` (associação label↔input), `ErrorBanner`,
  estados de loading/disabled, aviso sobre a chave (exibida 1x), texto sem
  emojis indevidos.
- **C8 (E2E)** — `e2e-tests/tests/register-ui.spec.js` **2/2 passaram**
  (cadastro nome/e-mail/senha + termos → API key 1x; link "Criar conta" no
  login) — executado 2026-08-17 contra a app real (banco `foxcash` local).
  O import `const { test } = require('@playwright/test')` foi adicionado na
  execução (sem ele o spec nem rodava — registrado na §Implementação).

**Correção de doc na auditoria:** `requirements.md` referenciava a Spec 63 como
`63-edge-cloudflare` → corrigido para `63-edge-waf-bot-mitigation`; README
(rotas públicas) ganhou `/register`. `docs-consistency` OK em todos os estados.
