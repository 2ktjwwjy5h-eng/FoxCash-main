---
name: Tela de cadastro do lojista no portal (Spec 39 UI)
status: audited
references:
  - "management/vision.md#V102"
  - "management/specs/39-email-registration/requirements.md"
  - "management/specs/12-api-authentication/requirements.md"
  - "management/specs/46-portal-frontend/requirements.md"
  - "management/specs/63-edge-waf-bot-mitigation/requirements.md"
  - "management/specs/73-frontend-ux/requirements.md"
  - "management/specs/141-fingerprint-portal/requirements.md"
---

# Spec 142 — Tela de cadastro do lojista no portal (Spec 39 UI)

## O que entrega

A **tela de cadastro do lojista no portal** (`portal/src/pages/RegisterPage.tsx`,
rota client-side `/register`), fechando o autosserviço de onboarding: a Spec 39
entregou o backend (`POST /api/v1/merchants` com nome/e-mail/senha) e o login já
existia (Spec 20/46) — faltava a porta de entrada para criar a conta sem
intervenção manual.

1. **Formulário de cadastro** — campos **nome** (≤120), **e-mail** (≤255,
   `type=email`) e **senha** (8–72, `autoComplete=new-password`), no padrão
   visual das telas de auth (`auth-card`, `Field`, `ErrorBanner` — Spec 73/46).
2. **Aceite dos termos** — checkbox "Li e concordo com os **termos de uso e
   condições**" com link para `docs/politicas-uso.md` (abre em nova aba). O
   botão **Criar conta** fica **desabilitado** enquanto o aceite não estiver
   marcado (e o handler re-valida por segurança).
3. **Submit** — `POST /api/v1/merchants` com `{name, email, password,
   turnstileToken?, fingerprintHash?}` em rota pública (`{auth: false}` —
   sem Bearer), mais **Turnstile** (Spec 63, Fase C — widget invisível apenas
   quando o backend expõe a site key) e **fingerprint de dispositivo** (Spec
   141).
4. **Sucesso — API key UMA única vez** — o backend devolve a chave apenas no
   cadastro (Spec 12); a tela exibe a chave em `.key-value` com botão
   **Copiar chave** (feedback "Copiada ✓"), avisa que ela não será exibida de
   novo (regenerar em Credenciais) e leva para `/login`.
5. **Erros explícitos** — e-mail duplicado (409 `merchant_already_exists`),
   validação (400) e falhas de rede aparecem no `ErrorBanner`; o botão é
   desabilitado durante o submit (`submitting`).
6. **Rota + entrada** — `/register` no `App.tsx` (rota pública, junto de
   `/login`) e link **"Criar conta"** na tela de login (`LoginPage.tsx`).
7. **Fix de rota pública da SPA** — `/register` adicionado à whitelist de
   rotas públicas do backend (`SecurityConfig.spaPublicPaths()`) e ao fallback
   da SPA (`PortalSpaController.forwardToIndex()`): acesso direto/F5/deep-link
   servia **401 JSON** em vez do `index.html` (o `page.goto('/register')` do
   E2E também quebrava). Sem esse fix, o login funciona mas o cadastro não.

## Contexto (por que agora)

A Spec 39 (audited 2026-08-05) mudou o cadastro para **apenas nome/e-mail/senha**
(sem CPF/CNPJ) e fez o e-mail virar o identificador de login — mas a UI do portal
só tinha o login (Spec 46). O onboarding de um lojista novo dependia de um cadastro
via API ou de intervenção manual. Esta spec fecha o círculo: o link "Criar conta"
na tela de login leva ao formulário, e a conta criada já devolve a API key.

## Critérios de aceite

1. **Formulário completo** — campos nome (maxLength 120), e-mail (type=email,
   maxLength 255, `autoComplete=username`) e senha (minLength 8, maxLength 72,
   `autoComplete=new-password`) renderizados com labels (padrão `Field`).
2. **Termos obrigatórios** — checkbox "Li e concordo com os termos de uso e
   condições"; **botão "Criar conta" desabilitado** sem o aceite; link abre
   `docs/politicas-uso.md` em nova aba (`target=_blank rel=noopener`).
3. **Submit correto** — `POST /api/v1/merchants` (rota pública, `auth:false`)
   com `name/email/password` (+ `turnstileToken`/`fingerprintHash` quando
   disponíveis); botão desabilitado durante a chamada; e-mail duplicado →
   `ErrorBanner` com a mensagem do 409.
4. **Sucesso com API key 1x** — após 201: tela "Conta criada — guarde sua chave
   de API" com a chave no box `.key-value`, botão **Copiar chave** (feedback) e
   **Fazer login** → `/login`.
5. **Rota e entrada** — `/register` roteada no `App.tsx` (pública) e link
   "Criar conta" no `LoginPage`.
6. **Rota pública da SPA (fix)** — `GET /register` direto (F5/deep-link) →
   **200 `text/html`** (index.html da SPA), não 401; `/register` presente em
   `SecurityConfig.spaPublicPaths()` e `PortalSpaController`.
7. **Acessibilidade/UX** — labels associadas (`Field`), estados de
   loading/erro, sem emojis indevidos, texto de aviso sobre a chave de API.
8. **Testes E2E** — `e2e-tests/tests/register-ui.spec.js`: (a) cadastro
   nome/e-mail/senha + termos → conta criada + API key exibida uma vez; (b)
   tela de login tem o link "Criar conta".

## Dependências

- **Spec 39** (backend cadastro) — `POST /api/v1/merchants`, validações,
  `merchant_already_exists` (409).
- **Spec 12** (API key) — chave devolvida uma única vez no cadastro.
- **Spec 46** (portal foundation) — `App.tsx`, `apiClient` (`{auth:false}`),
  rotas públicas da SPA.
- **Spec 63** (Fase C) — widget Turnstile no login/cadastro (`site key`).
- **Spec 73** (UX) — `Field`, `ErrorBanner`, `copyToClipboard`, padrão das
  telas de auth.
- **Spec 141** (fingerprint) — `getFingerprintHash()` enviado no cadastro.

## Fora de escopo

- **Backend do cadastro** — Spec 39 (audited).
- **Alterações no contrato** — o request/response da Spec 39 não muda.
- **CPF/CNPJ ou KYC** — decisão 2026-08-05 (sem documento no cadastro).
- **Fluxo de e-mail de verificação / confirmação de conta** — não existe na
  Spec 39 (a conta já nasce ACTIVE); se surgir, é spec nova.
- **Recuperação de senha / MFA** — Specs 40/94 (telas já existentes).
- **Master Console** — nada muda.
