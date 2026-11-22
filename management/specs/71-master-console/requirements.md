---
name: Master Console — frontend web do perfil master (fatia 1)
status: audited
references:
  - "management/vision.md#V51"
  - "management/specs/13-master-auth/requirements.md"
  - "management/specs/44-mfa-totp-master/requirements.md"
  - "management/specs/46-portal-frontend/requirements.md"
  - "management/specs/69-rbac-master-stepup/requirements.md"
  - "management/specs/15-master-dashboard/requirements.md"
  - "management/specs/41-master-profiles-activity/requirements.md"
  - "management/specs/37-master-audit/requirements.md"
  - "management/specs/8-admin-dashboard/requirements.md"
  - "management/specs/67-operator-bot/requirements.md"
  - "management/specs/14-withdrawals/requirements.md"
  - "management/specs/16-antifraud-compliance/requirements.md"
  - "management/specs/19-antifraud-real/requirements.md"
  - "management/specs/27-wallet-ledger/requirements.md"
  - "management/specs/30-wallet-reconciliation/requirements.md"
  - "management/specs/33-invoice-collection-receivables/requirements.md"
  - "management/specs/34-master-ledger/requirements.md"
---

# Spec 71 — Master Console: frontend web do perfil master (fatia 1)

## O que entrega

Primeiro frontend web para o perfil master — hoje a única interface
administrativa é o Operator Bot (Spec 67, Discord). O backend master está
**100% especificado e auditado** (Specs 13/15/41/37/44; RBAC+step-up da Spec 69
`verified`); esta spec entrega a **SPA do Master Console (fatia 1 — leitura +
OPERATOR, sem step-up)**, thin client sobre as APIs `ROLE_MASTER`, no mesmo
padrão do portal do lojista (Spec 46).

**Decisões do usuário (2026-08-11):** escopo em 2 fatias — esta Spec 71
(leitura + ações reversíveis de OPERATOR) e Spec 72 (ações sensíveis de
FINANCE/ADMIN com step-up TOTP + gestão de perfis master). As permissões do
master autenticado são expostas via **`GET /api/v1/master/me`** (endpoint novo,
espelho do `/portal/me` do lojista). As telas de Lojistas e Lookup IP exigem
**espelhos read-only** sob `/api/v1/master/**` (hoje os dados só existem sob
`/api/v1/admin/**`, que exige `ROLE_ADMIN` — o master não alcança).

### Backend aditivo (somente leitura, `ROLE_MASTER`, contratos existentes intactos)

1. **`GET /api/v1/master/me`** — perfil do master autenticado com
   `permissions[]` + `role` + `canManageMasters` + `mfaActive` (base para a UI
   RBAC-aware). Espelho do `/portal/me` (Spec 20/46) para o master.
2. **`GET /api/v1/master/merchants`** — listagem paginada de lojistas
   (filtros `status`/`q`/`from`/`to`), espelho read-only do
   `/api/v1/admin/merchants` (Spec 8) — o master precisa navegar até o detalhe
   de um lojista (earnings/fees/risco/ledger/receivables).
3. **`GET /api/v1/master/lookup/ip/{ip}`** — lookup de lojista por IP,
   espelho read-only do `/api/v1/admin/lookup/ip/{ip}` (Spec 67, `!ip` do bot).

### Frontend (SPA `console/`)

- **Login + MFA** (Specs 13/44): usuário/senha; campo TOTP no formulário
  (401 uniforme sem oráculo — com MFA ativo, a senha não basta); logout revoga;
  autogestão de MFA (activate 2 passos/confirm/deactivate/recovery codes).
- **UI RBAC-aware** (Spec 69): navegação e botões **filtrados por
  `permissions[]`**; o backend continua a autoridade (403 `forbidden`
  deny-by-default; a UI esconde, nunca autoriza).
- **Telas de leitura** (todas ROLE_MASTER existentes): dashboard master,
  transações, lojistas (lista + detalhe com earnings/fees/risco/ledger/
  receivables/saques), saques, financeiro/ledger do master, auditoria,
  relatório de atividades, reconciliação, lookup por IP.
- **Ações reversíveis de OPERATOR** (sem step-up): suspender/liberar lojista
  (Spec 16) e bloquear/liberar saques (Spec 14) — com confirmação e feedback.

**A fatia 2 (Spec 72) fica fora:** saque administrativo, write-off, cobrança
manual de fatura e gestão de perfis master (ações sensíveis + step-up TOTP).

## Contexto (por que agora)

O usuário pediu (2026-08-11) a **proposta de frontend para o master** após a
discussão de arquitetura consolidar o princípio: *"o frontend nunca decide se o
master pode executar uma operação — o backend verifica autenticado → é master?
→ permissão da ação → escopo do recurso → step-up → executa → audit log"*. O
backend já entrega tudo o que o console consome (dashboard, saques, risco,
auditoria, atividades, receivables, ledger do master, reconciliação) e a Spec
69 (`verified`) fecha a camada de permissões + step-up que a UI RBAC-aware
precisa. O Operator Bot (Spec 67) cobre operação via chat; o console cobre o
uso visual completo. Fatia 1 primeiro porque: (a) as ações reversíveis de
OPERATOR não exigem step-up (Spec 69, critério 8 — suporte ágil); (b) as
sensíveis exigem o fluxo de step-up, que fica com a fatia 2.

## Critérios de aceite

1. **`GET /api/v1/master/me`** (ROLE_MASTER, novo): → 200 `{masterId,
   username, name, role, permissions[], canManageMasters, mfaActive}` — sem
   hash de senha, sem PII extra; 401 sem token válido; **rota de leitura** (sem
   guard de permissão — todo master autenticado se vê). `role`/`permissions[]`
   vêm do catálogo da Spec 69 resolvido por nível (`can_manage_masters` —
   decisão do usuário 2026-08-11): OPERATOR → bloco operacional completo (7
   permissões); ADMIN → bloco + `master.profiles.manage` (nunca `[]` para master
   ativo).
2. **`GET /api/v1/master/merchants`** (ROLE_MASTER, novo): mesmo shape e
   validações do `/api/v1/admin/merchants` (Spec 8 — paginação, filtros
   `status`/`q`/`from`/`to`, 400 `invalid_argument` em parâmetro inválido,
   cap size 100). Contrato admin **inalterado**.
3. **`GET /api/v1/master/lookup/ip/{ip}`** (ROLE_MASTER, novo): mesmo shape do
   `/api/v1/admin/lookup/ip/{ip}` (Spec 67 — `{matches[]}` com resumo de vendas
   + bot da loja, máscara de PII); 400 `invalid_argument` em IP inválido;
   200 com `matches` vazio sem associação. Contrato admin **inalterado**.
4. **SPA servida**: build de `console/` → `src/main/resources/static/console/`,
   servido pelo Spring Boot com flag `foxcash.console.serve-frontend` (default
   `false`); rotas client-side (`/console/*`) encaminhadas para o
   `console/index.html`; CSP da SPA ativa quando o console (ou o portal) está
   sendo servido; CORS apenas para origins configurados; **sem o flag, nenhuma
   rota extra é exposta** (API pura, CSP restrita — padrão Spec 46).
5. **Login com MFA no cliente**: `POST /api/v1/auth/master/login`
   `{username, password, totpCode?}`; com MFA ativo a senha não basta (401
   uniforme — sem oráculo); token opaco em `sessionStorage` (chave própria do
   console, `foxcash.console.token`), `Authorization: Bearer`; interceptor 401
   → logout local + redirect `/console/login` (exceto na própria rota pública
   de login — UX do B-A8, padrão Spec 46); logout explícito revoga no servidor.
6. **UI RBAC-aware**: `usePermission(p)` esconde navegação/botões sem a
   permissão; papel exibido no header; ao receber 403 `forbidden` do backend a
   UI mostra "sem permissão" (e remove o item do menu se aplicável) — **a UI
   nunca decide autorização**, só apresentação.
7. **Telas de leitura** (todas via APIs ROLE_MASTER existentes + os 3 endpoints
   novos): Dashboard master (Spec 15 — transações recentes, earnings/fees,
   revenue summary), Transações (15), Lojistas (lista + detalhe:
   earnings/fees/risco/ledger/receivables/withdrawals — 15/16/19/27/33/14),
   Saques (14/32/36), Financeiro do master (34 — summary/ledger), Receivables
   (33 — leitura), Auditoria (37), Relatório de atividades (41),
   Reconciliação (30), Lookup IP (67).
8. **Ações de OPERATOR** (com a permissão, sem step-up): `POST
   /master/merchants/{id}/suspend|release` (Spec 16 — `merchant.suspend`/
   `merchant.release`) e `POST /master/merchants/{id}/withdrawals/block|release`
   (Spec 14 — `withdrawal.block`/`withdrawal.release`); botões só com a
   permissão; confirmação antes de executar; feedback de sucesso/erro; 403 do
   backend tratado como "sem permissão".
9. **Autogestão de MFA no console** (Spec 44, sempre o próprio perfil):
   `POST /master/mfa/activate` (otpauthUri + manualEntryKey exibidos **1x**),
   `confirm` (primeiro TOTP → recovery codes exibidos **1x**), `deactivate`
   (senha + TOTP/recovery → 204), `recovery-codes` (regenera). `mfaActive` do
   `/me` alimenta o estado da tela.
10. **E2E browser** (`master-console-ui.spec.js`, Playwright): login (sem MFA)
    → dashboard → lista de lojistas → detalhe → ação OPERATOR (suspend) → login
    com MFA ativo (TOTP) → 401 no login sem TOTP → logout → 401/redirect. 403
    `forbidden`/`step_up_required` não derrubam a sessão.
11. **Sem regressão**: `mvn -o clean test` verde (testes novos: `/me`,
    espelhos, controller); E2E completo verde (suíte existente + novo spec
    browser); contratos `/api/v1/admin/**` e `/api/v1/master/**` existentes
    **inalterados**.
12. **Dependência explícita da Spec 69**: `permissions[]`/`role` do `/me` e os
    guards das ações vêm da Spec 69 (catálogo `MasterPermission` + guard
    tier-based por nível — decisão do usuário 2026-08-11) — o console
    **consome, não duplica**; implementar a Spec 71 após a Spec 69.

## Fora de escopo

- **Ações sensíveis + step-up TOTP** (saque administrativo, write-off, cobrança
  manual, perfis master) — Spec 72.
- **Gestão de perfis master** (CRUD de masters) — Spec 72 (sensível + step-up).
- **Mudança de contrato** em `/api/v1/admin/**`, `/api/v1/master/**` e
  `/api/v1/auth/master/**` existentes — os 3 endpoints novos são **aditivos**.
- **Master Console em chat** (Operator Bot com step-up por chat) — decisão de
  produto futura.
- **RBAC de lojista** (portal) — o portal já tem escopo por principal.
- **OpenAPI/contratos gerados** (`master-client`) — spec futura.
