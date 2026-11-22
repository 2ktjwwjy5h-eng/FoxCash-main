---
name: Master Console — frontend web do perfil master (fatia 2)
status: audited
references:
  - "management/vision.md#V51"
  - "management/specs/71-master-console/requirements.md"
  - "management/specs/69-rbac-master-stepup/requirements.md"
  - "management/specs/41-master-profiles-activity/requirements.md"
  - "management/specs/32-withdrawal-destination-flow/requirements.md"
  - "management/specs/33-invoice-collection-receivables/requirements.md"
  - "management/specs/34-master-ledger/requirements.md"
  - "management/specs/44-mfa-totp-master/requirements.md"
  - "management/specs/8-admin-dashboard/requirements.md"
---

# Spec 72 — Master Console: frontend web do perfil master (fatia 2)

## O que entrega

**Fatia 2 do Master Console** (Spec 71 = fatia 1: leitura + OPERATOR sem
step-up). Esta spec entrega as **ações sensíveis** com **step-up TOTP no
cliente** + a **gestão de perfis master** (função admin) — fechando a
**interface web completa do perfil master** (Visão V51).

O backend já está **100% implementado e auditado** — nada de contrato HTTP muda
nesta spec. As 4 ações sensíveis (saque administrativo Spec 32, write-off Spec
34, cobrança manual Spec 33, gestão de perfis Spec 41) já têm:
- **Guards de permissão** no service (Spec 69 — `MasterPermissionGuard`,
  deny-by-default, 403 `forbidden`);
- **Guard de step-up** no service (Spec 69 — `MasterStepUpGuard`, 403
  `step_up_required` quando a sessão não tem `stepped_up_at` na janela de
  15 min);
- **Endpoint `POST /api/v1/master/mfa/step-up`** (Spec 69 — re-prova de posse
  TOTP/recovery, reuso do `verifySecondFactor`: anti-replay + lockout).

O que esta spec adiciona:

1. **Espelho read-only `GET /api/v1/master/invoices`** (ROLE_MASTER, aditivo):
   o master precisa listar as faturas de um lojista para a **cobrança manual**
   (Spec 33), mas `GET /api/v1/admin/invoices` exige `ROLE_ADMIN` — mesmo
   padrão dos espelhos da Spec 71 (`/master/merchants`, `/master/lookup/ip/{ip}`).
2. **Step-up TOTP no cliente**: ao receber **403 `{code: "step_up_required"}`**
   numa ação sensível, o console abre um **prompt TOTP** → chama
   `POST /api/v1/master/mfa/step-up` → **repete a ação automaticamente**.
   Janela de 15 min por sessão respeitada no cliente (cache do `stepped_up_at`
   para UX); **nunca 401** — a sessão não cai.
3. **Telas/ações da fatia 2**:
   - **Saque administrativo** (Spec 32) — em Financeiro/detalhe do lojista;
   - **Write-off de receivable** (Spec 34) — na listagem de receivables;
   - **Cobrança manual de fatura** (Spec 33) — no detalhe do lojista (novo
     painel de faturas via espelho de invoices);
   - **Gestão de perfis master** (Spec 41/69) — nova tela "Perfis master"
     (admin-only, `master.profiles.manage`): listar, criar (senha temporária
     exibida 1×), ativar/desativar — com step-up.

**A fatia 1 (Spec 71) permanece**: leitura completa + ações reversíveis de
OPERATOR (suspend/release/block/release) **sem** step-up.

## Contexto (por que agora)

A fatia 1 (Spec 71) entregou o console de leitura + operação reversível. A
Spec 69 (`audited`) já protege as 4 ações sensíveis no backend com
permissão + step-up, e o E2E da Spec 69 (`stepup.spec.js`) prova o fluxo
HTTP (403 `step_up_required` → step-up → repete → 200). Falta **a UX no
console**: sem esta spec, o master vê as telas de leitura mas não consegue
executar as ações sensíveis pela web (só via API/Operator Bot). Esta spec
fecha o console (Visão V51: "a interface web completa e segura").

Decisão do usuário (2026-08-11): escopo em 2 fatias — a fatia 2 concentra
tudo que exige step-up TOTP + a gestão de perfis (função admin).

## Critérios de aceite

1. **Espelho `GET /api/v1/master/invoices`** (ROLE_MASTER, novo): mesmo shape e
   validações do `/api/v1/admin/invoices` (Spec 8 — filtros `merchantId`/
   `status`/`billingPeriod`, paginação, cap 100, 400 `invalid_argument`).
   Contrato admin **inalterado**. Reuso do `AdminDashboardUseCase.searchInvoices`.
2. **Step-up no cliente — contrato**: qualquer chamada a uma rota de ação
   sensível que receba **403 `{code: "step_up_required"}`** abre o prompt TOTP
   (não derruba a sessão — **nunca** logout/redirect para 403); o prompt chama
   `POST /api/v1/master/mfa/step-up {code}` e, em 200, **repete a ação
   original automaticamente**; em falha do step-up (401/400/429), mostra erro
   e mantém a ação pendente para nova tentativa.
3. **Saque administrativo** (Spec 32): formulário em Financeiro (e/ou detalhe
   do lojista) com `merchantId`, `amount`, `destinationKey` e `reason`
   (obrigatórios — 400 `invalid_argument`/`validation_error` do backend
   refletidos); confirmação antes de executar; 201 → feedback com saldo
   pós-débito; 403 `step_up_required` → prompt TOTP → repete (critério 2);
   409 `insufficient_balance`/`merchant_not_active` → erro visível.
4. **Write-off de receivable** (Spec 34): botão na listagem de receivables
   (com a permissão `receivable.write_off`); confirmação com `reason`
   obrigatório (400 `reason_required` refletido); 200 → feedback com `loss`;
   step-up conforme critério 2.
5. **Cobrança manual de fatura** (Spec 33): painel de faturas no detalhe do
   lojista (via `GET /master/invoices?merchantId=` — critério 1) com botão
   "Cobrar" em faturas `PENDING`/`OVERDUE`; 200 `{debit, remaining}` →
   feedback; step-up conforme critério 2.
6. **Gestão de perfis master** (Spec 41/69, admin-only): nova tela "Perfis
   master" visível apenas com `master.profiles.manage` (UI RBAC-aware — o
   backend continua a autoridade); listar perfis (`GET /master/profiles` —
   **leitura: exige admin mas sem step-up** — `MasterAdminGuard.requireAdmin`),
   criar (`POST /master/profiles` — username/name; **senha temporária exibida
   1×** com aviso de guardar), ativar/desativar (`POST .../{id}/activate|
   deactivate`); **criar/ativar/desativar exigem step-up** (o
   `MasterProfileService` aplica `stepUpGuard.requireSteppedUp(actor,
   MASTER_PROFILES_MANAGE)` nas 3 escritas; a listagem é somente-leitura).
   **403 `forbidden`** em master sem `can_manage_masters` → banner "sem
   permissão" (sessão permanece).
7. **UI RBAC-aware (Spec 69)**: botões de ações sensíveis renderizados apenas
   com a permissão correspondente (`withdrawal.administrative`,
   `receivable.write_off`, `invoice.collect`, `master.profiles.manage`);
   ao receber 403 `forbidden` do backend, banner "sem permissão" + remoção do
   item do menu quando aplicável — a UI nunca autoriza, só apresenta.
8. **Janela de step-up no cliente**: após um step-up bem-sucedido, o console
   **cacheia o instante** e não reabre o prompt para ações sensíveis na mesma
   janela (15 min — a janela REAL é do backend: se o `stepped_up_at` expirou,
   o backend responde 403 `step_up_required` de novo e o prompt reabre).
   **Fail-closed**: o cache nunca "autoriza" sozinho — a repetição da ação é
   sempre tentada e o 403 do backend é a única fonte de verdade.
9. **Autogestão de MFA permanece** (Spec 71, fatia 1): a tela de Segurança
   (MFA) já existe e não muda; o step-up reusa o mesmo TOTP do login (o
   master com MFA ativo usa o mesmo código do app).
10. **E2E browser** (`master-console-stepup.spec.js`, Playwright, serial):
    login → ativar MFA via API → ação sensível (write-off ou saque
    administrativo) **sem step-up** → 403 `step_up_required` → prompt TOTP →
    `POST /step-up` → ação repetida → 200/201; **sessão permanece viva** após
    o 403 (navegação intacta); janela expirada → prompt reabre; perfil master
    sem `can_manage_masters` → tela de Perfis escondida + 403 `forbidden` sem
    logout. Cobre também o fluxo de criar perfil (senha temporária 1×).
11. **Sem regressão**: `mvn -o clean test` verde (testes novos: espelho
    invoices); E2E completo verde (suíte existente + novo spec browser —
    `master-console-stepup.spec.js`); contratos `/api/v1/admin/**` e
    `/api/v1/master/**` existentes **inalterados** (o espelho é aditivo).
12. **Dependências explícitas**: step-up/guards da Spec 69 (backend pronto);
    espelho de invoices reusa `AdminDashboardUseCase.searchInvoices` (Spec 8 —
    o use case não tem role embutida; a fronteira de role é o controller);
    padrão de SPA da Spec 46/71; `permissions[]` do `/me` (Spec 71) alimenta a
    UI RBAC-aware.

## Fora de escopo

- **Mudança de contrato** em `/api/v1/admin/**`, `/api/v1/master/**` e
  `/api/v1/auth/master/**` existentes — o único endpoint novo é o espelho
  `GET /master/invoices` (aditivo).
- **Step-up via Operator Bot** (Discord) — decisão de produto futura.
- **Gestão de TOTP de terceiros** (o admin gerencia perfis, mas o MFA de cada
  master é autogestão — Spec 44).
- **OpenAPI/contratos gerados** (`master-client`) — spec futura.
- **Ações de leitura novas** — além do espelho de invoices, nada de leitura
  nova (o console já lê tudo da fatia 1).
