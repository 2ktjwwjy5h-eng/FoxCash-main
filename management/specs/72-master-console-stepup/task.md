---
name: Master Console — frontend web do perfil master (fatia 2)
status: audited
---

# Spec 72 — Task: Master Console (fatia 2)

> **Pré-requisito**: Spec 71 `audited` (console da fatia 1 + `permissions[]` do
> `/me` + `MasterReadController` para o espelho de invoices) e Spec 69
> `audited` (step-up + guards + `POST /master/mfa/step-up`). O backend das
> ações sensíveis (Specs 32/33/34/41) já está pronto — esta spec é
> **majoritariamente frontend** + 1 espelho read-only.

## F0 — Backend aditivo: `GET /api/v1/master/invoices` (critério 1)

- [x] T0.1: `MasterReadController.invoices` — espelho read-only do
      `/api/v1/admin/invoices` (D2): filtros `merchantId`/`status`/
      `billingPeriod` + `page`/`size` (cap 100), reuso do
      `AdminDashboardUseCase.searchInvoices` + `InvoiceResponse` +
      `PageResponse`; parâmetro inválido → 400 `invalid_argument`.
- [x] T0.2: Testes — `MasterReadControllerTest` +3: delegation com filtros
      parseados (`merchantId` UUID, `status`, `billingPeriod`), 400 em
      `billingPeriod`/`status` inválidos, clamp size 100. Contrato
      `/api/v1/admin/invoices` inalterado (testes do `AdminController` verdes).
- [x] T0.3: `mvn -o test` verde (suíte completa sem regressão).

## F1 — Step-up TOTP no cliente (critérios 2, 8)

- [x] T1.1: `apiClient.ts` — `ApiError.stepUpRequired` (derivado de
      `status === 403 && body.code === 'step_up_required'`); **nada de
      logout/redirect em 403** (o interceptor já só desloga em 401 — Spec
      71/D7.4).
- [x] T1.2: `hooks/useStepUp.tsx` — `run(action)`: tenta a ação; em 403
      `step_up_required` → se `steppedUpAt` dentro da janela (15 min, espelho
      da `MasterStepUpProperties.window`), repete a ação; senão abre o prompt
      TOTP → `POST /api/v1/master/mfa/step-up {code}` → 200 grava
      `steppedUpAt` e repete a ação (1 tentativa); falha do step-up
      (401/400/429) → erro no prompt, mantém aberto; cancelar → rejeita com o
      `ApiError` original. **Fail-closed**: o cache nunca autoriza sozinho.
- [x] T1.3: `components/StepUpPrompt.tsx` — modal TOTP (padrão
      `ConfirmDialog`): título "Reautenticação necessária", campo de código
      (autofocus + Enter), Confirmar/Cancelar, erro do backend visível, sem
      secret/oráculo.
- [x] T1.4: Integração do `useStepUp` no `apiClient`/contexto: o hook é
      importado nas páginas (não global) — cada ação sensível chama
      `useStepUp().run(...)`.
- [x] T1.5: `npm run build` do console limpo (`tsc` sem erro).

## F2 — Telas/ações sensíveis (critérios 3–7)

- [x] T2.1: **Saque administrativo** (`FinancialPage` + `MerchantDetailPage`):
      formulário `merchantId` (select de `GET /master/merchants`) + `amount` +
      `destinationKey` + `reason` → `ConfirmDialog` → `useStepUp.run(api.post(
      '/api/v1/master/merchants/{id}/withdrawals/administrative'))` → 201 com
      saldo pós-débito; 409 `insufficient_balance`/`merchant_not_active` →
      `ErrorBanner`. `RequirePermission("withdrawal.administrative")`.
- [x] T2.2: **Write-off** (`ReceivablesPage`): botão por receivable
      `OPEN`/`PARTIAL` → `ConfirmDialog` com `reason` obrigatório →
      `useStepUp.run(api.post('/api/v1/master/receivables/{id}/write-off',
      { reason }))` → 200 `{loss}` → feedback + recarregar; 400
      `reason_required` → erro. `RequirePermission("receivable.write_off")`.
- [x] T2.3: **Cobrança manual** (`MerchantDetailPage`): aba "Faturas" via
      `GET /master/invoices?merchantId={id}` → botão "Cobrar" em
      `PENDING`/`OVERDUE` → `ConfirmDialog` → `useStepUp.run(api.post(
      '/api/v1/master/merchants/{id}/invoices/{invoiceId}/collect'))` → 200
      `{debit, remaining}` → feedback + recarregar.
      `RequirePermission("invoice.collect")`.
- [x] T2.4: **Gestão de perfis** (`ProfilesPage` nova): item no
      `NavigationMap` com `permission: 'master.profiles.manage'` (só ADMIN);
      lista `GET /master/profiles`; criar `POST /master/profiles` →
      `temporaryPassword` exibida **1×** (modal com aviso "Guarde — não será
      exibida novamente" + botão "Já copiei"); ativar/desativar
      `POST .../{id}/activate|deactivate` → 204 → recarregar. Tudo com
      `RequirePermission` + `useStepUp.run`.
- [x] T2.5: 403 `forbidden` (sem permissão) → banner "sem permissão" + sessão
      permanece (nunca logout) — padrão da fatia 1.
- [x] T2.6: `App.tsx` ganha a rota `/console/profiles`; `types.ts` ganha
      `Invoice`, `MasterProfile`, `CollectionResult`, `WriteOffResult`.
- [x] T2.7: `npm run build` do console limpo após todas as telas.

## F3 — E2E + fechamento (critérios 10, 11, 12)

- [x] T3.1: `master-console-stepup.spec.js` (D6, serial, padrão
      `master-console-ui.spec.js` + `stepup.spec.js`): seed SQL + MFA via API
      com `totpAt` (reuso do `helpers/totp.js`); cenários 1–5 (write-off com
      prompt TOTP, sessão viva após 403, janela expirada via SQL, perfis
      admin-only + criar com senha 1×, saque administrativo com step-up).
- [x] T3.2: `mvn -o clean test` verde — suíte completa sem regressão (F0).
- [x] T3.3: E2E completo verde (`master-console-ui` + `master-console-stepup`
      + demais specs — app com `NYXCASH_CONSOLE_SERVE_FRONTEND=true`; env já
      no job e2e do `ci.yml` — T6.4 da Spec 71).
- [x] T3.4: README (seção curta: ações sensíveis + step-up no console) e sync
      roadmap/requirements/design/task (status `implemented`).
- [x] T3.5: `/spec-audit` — verificar os **12 critérios** do requirements um a
      um (padrão Spec 71).

## Verificação (`/spec-verify` 2026-08-12)

**12 critérios checados contra o código; 2 achados de precisão corrigidos nos
documentos (sem mudança de contrato):**

1. **Espelho de invoices (critério 1)** — `AdminDashboardUseCase.searchInvoices`
   existe (Spec 8); `QueryParamParser` tem `parseUuid`/`parseEnum`/
   `parsePeriod`/`clampPage`/`clampSize`; `InvoiceSearchQuery`,
   `InvoiceStatus` (PENDING/PAID/OVERDUE/CANCELLED) e `InvoiceResponse` (id,
   merchantId, billingPeriod, transactionCount, transactionFeesTotal,
   monthlyFee, totalAmount, status, dueDate, createdAt) existem — o espelho no
   `MasterReadController` precisa apenas de 3 imports novos (mesmo padrão do
   `AdminController`). ✅
2. **Step-up no cliente (critérios 2, 8)** — `MasterStepUpProperties`
   (`required-permissions` = 4 ações sensíveis, `window` = 15 min, fail-closed);
   `POST /master/mfa/step-up` (reuso do `verifySecondFactor`: anti-replay +
   recovery + lockout; 200 em sucesso); `MasterStepUpRequiredException` →
   GlobalExceptionHandler → 403 `step_up_required`; `apiClient` da Spec 71 já
   propaga 403 como `ApiError` (nunca logout). ✅
3. **Ações sensíveis (critérios 3–6)** — endpoints verificados:
   `POST /master/merchants/{id}/withdrawals/administrative`
   (`AdministrativeWithdrawalRequest`: amount/destinationKey/reason →
   201 `WithdrawalResponse` com `walletBalance`), `POST /master/receivables/
   {id}/write-off` (`WriteOffRequest.reason` → 200 `{loss}`),
   `POST /master/merchants/{id}/invoices/{invoiceId}/collect` (→ 200
   `{debit, remaining}`), `GET/POST /master/profiles` + `/{id}/activate|
   deactivate` (`MasterCreatedProfileResponse.temporaryPassword` 1×).
   Guards verificados: `MasterPermissionGuard` + `MasterStepUpGuard` nos 4
   services (`MasterWithdrawalService`, `ReceivableWriteOffService`,
   `InvoiceCollectionService`, `MasterProfileService`). ✅
4. **Achado corrigido (critério 6)**: o `list` de perfis usa
   `MasterAdminGuard.requireAdmin` (**sem step-up** — é leitura) enquanto
   criar/ativar/desativar aplicam `stepUpGuard.requireSteppedUp(actor,
   MASTER_PROFILES_MANAGE)` (linhas 79/107/130). Requirements/design
   atualizados para distinguir: listagem = admin sem step-up; escritas =
   admin + step-up. ✅
5. **Achado corrigido (design D4.4)**: o texto "todas com step-up" foi
   substituído pela regra exata (listagem sem step-up; escritas com step-up),
   com o nome real do guard (`MasterAdminGuard.requireAdmin`) — padrão do
   design da Spec 69. ✅
6. **Demais critérios (7, 9–12)** — RBAC-aware reusa `usePermission`/
   `RequirePermission` (Spec 71); MFA autogestão já entregue (Spec 71);
   padrão E2E = `master-console-ui.spec.js` + `stepup.spec.js` (Spec 69) com
   `helpers/totp.js`; sem regressão = `mvn test` + E2E completo (mesmas envs
   do CI); dependências explícitas (Specs 69/8/46/71) referenciadas no
   frontmatter. ✅

Referências validadas: V51 (visão), specs 71/69/41/32/33/34/44/8 (contratos e
guards), `MasterStepUpProperties`/`MasterStepUpService`/`MasterStepUpGuard`/
`GlobalExceptionHandler` (comportamento do step-up), `QueryParamParser`
(métodos do espelho). Rastreabilidade critérios ↔ design ↔ task 1:1 (D8).
Pronta para `/spec-implement`.

## Implementação (`/spec-implement` 2026-08-12)

Backend aditivo + frontend da fatia 2 entregues:

- **F0 (espelho invoices)**: `MasterReadController.invoices` — `GET
  /api/v1/master/invoices` reusando `AdminDashboardUseCase.searchInvoices` +
  `QueryParamParser` (parseUuid/parseEnum/parsePeriod/clampPage/clampSize);
  `MasterReadControllerTest` +3 casos (delegation com filtros, 400 em
  `billingPeriod`/`status`, clamp size 100).
- **F1 (step-up no cliente)**: `ApiError.stepUpRequired` (`403` +
  `code==='step_up_required'`, nunca logout); `hooks/useStepUp.tsx`
  (`run(action)` → 403 step_up_required → cache 15min (UX, fail-closed) →
  prompt → `POST /master/mfa/step-up` → repete a ação; falha 401/400/429 →
  erro no prompt, mantém aberto); `components/StepUpPrompt.tsx` (modal TOTP).
- **F2 (telas/ações)**: `FinancialPage` (saque administrativo —
  `withdrawal.administrative`), `ReceivablesPage` (write-off —
  `receivable.write_off`), `MerchantDetailPage` (aba "Faturas" via espelho +
  botão "Cobrar" em PENDING/OVERDUE — `invoice.collect`),
  `ProfilesPage` nova (lista sem step-up; criar com senha temporária 1× e
  ativar/desativar com step-up — `master.profiles.manage`), item de menu
  permissionado no `Layout`, rota `/console/profiles` no `App.tsx`, tipos em
  `types.ts`.
- **F3 (E2E + fechamento)**: `master-console-stepup.spec.js` (serial, seed
  SQL + MFA via API com `totpAt` do `helpers/totp.js`) — 5/5 verde; `mvn
  test` 1376/0/0; README citando a fatia 2 com step-up.

## Auditoria (`/spec-audit` 2026-08-12)

**12 critérios do requirements verificados um a um contra o código e o E2E
executado (5/5); 0 achados de código.**

1. **Espelho `GET /master/invoices` (c1)** — `MasterReadController.invoices`
   (ROLE_MASTER, mesmo shape/validações do admin: filtros, paginação, cap 100,
   400 `invalid_argument`); reuso do `AdminDashboardUseCase.searchInvoices`;
   contrato admin inalterado; `MasterReadControllerTest` +3 verdes. ✅
2. **Step-up no cliente (c2, c8)** — `ApiError.stepUpRequired` + `useStepUp`
   (`run` → prompt → `POST /master/mfa/step-up` → repete a ação; 401/400/429 →
   erro no modal, ação pendente; cancelar → rejeita com o ApiError original);
   cache 15min só UX (fail-closed — o 403 do backend é a verdade; E2E cenário
   3 prova com `stepped_up_at` expirado via SQL → prompt reabre). Nunca 401. ✅
3. **Saque administrativo (c3)** — `FinancialPage`: select de lojistas +
   `amount`/`destinationKey`/`reason` obrigatórios → `ConfirmDialog` →
   `useStepUp.run(api.post(.../withdrawals/administrative))` → 201 com saldo
   pós-débito; 409 `insufficient_balance`/`merchant_not_active` → banner. E2E
   cenário 5 (saque com step-up → 201 + saldo). ✅
4. **Write-off (c4)** — `ReceivablesPage`: botão por receivable →
   `ConfirmDialog` com `reason` obrigatório → `useStepUp.run(api.post(
   .../receivables/{id}/write-off, {reason}))` → 200 `{loss}` → feedback. E2E
   cenário 1 (403 → prompt → step-up → repete → 200; linha vira PAID sem
   botão). ✅
5. **Cobrança manual (c5)** — `MerchantDetailPage` aba "Faturas" via
   `GET /master/invoices?merchantId=` → botão "Cobrar" em PENDING/OVERDUE com
   `RequirePermission("invoice.collect")` → `useStepUp.run(api.post(
   .../invoices/{id}/collect))` → 200 `{debit, remaining}` → feedback. ✅
6. **Perfis master (c6)** — `ProfilesPage`: lista `GET /master/profiles`
   (admin sem step-up); criar `POST /master/profiles` → senha temporária 1×
   (modal "Já copiei"); ativar/desativar `.../{id}/activate|deactivate` → 204
   → recarregar; escritas com step-up. E2E cenário 4 (OPERATOR escondido +
   403 forbidden com sessão viva; ADMIN cria perfil → senha 1×). ✅
7. **UI RBAC-aware (c7)** — `RequirePermission`/`usePermission` (Spec 71) nas
   4 ações + item de menu "Perfis master" com `permission:
   'master.profiles.manage'` (E2E cenário 4: OPERATOR não vê o menu); 403
   `forbidden` → banner, sessão permanece. ✅
8. **Janela de step-up (c8)** — cache `steppedUpAt` (15 min, espelho do
   backend) + fail-closed; a repetição da ação é sempre tentada (E2E cenário
   3: janela expirada → 403 de novo → prompt reabre). ✅
9. **Autogestão de MFA (c9)** — `SecurityPage` da fatia 1 inalterada; o
   step-up reusa o mesmo TOTP do login. ✅
10. **E2E browser (c10)** — `master-console-stepup.spec.js` **5/5** (2
    execuções seguidas com banco limpo): write-off com prompt TOTP, sessão
    viva após 403, janela expirada via SQL, perfis admin-only + criar com
    senha 1×, saque administrativo com step-up. ✅
11. **Sem regressão (c11)** — `mvn test` **1376/0/0** (suíte completa);
    contratos `/api/v1/admin/**` e `/api/v1/master/**` existentes inalterados
    (espelho aditivo); E2E novo verde com `NYXCASH_CONSOLE_SERVE_FRONTEND`
    (mesmas envs do CI). ✅
12. **Dependências (c12)** — Specs 69 (step-up/guards) e 8 (searchInvoices)
    `audited` antes; padrão SPA da Spec 46/71; `permissions[]` do `/me`
    (Spec 71) alimenta a UI RBAC-aware. ✅

**Achados corrigidos durante o E2E (ajustes de teste/ambiente — não são
falhas da spec):** (1) contrato do webhook PAID no cenário 5
(`eventId`/`externalReference`/`settledAt` — não `id`/`transactionId`);
(2) anti-replay do `verifySecondFactor`: o código do step-up deve ser de
janela distinta da do login (`totpAt(secret, 30)` — janela +30s, sempre
válida na ±1 e nunca replay) e o `loginAs` limpa `last_code_hash`/lockout
antes do login (o marcador persiste entre testes na MESMA janela de 30s);
(3) contrato do `PixChargeRequest` no cenário 5 (faltavam `merchantId`,
`expirationSeconds`, `idempotencyKey` única — Spec 28); (4) `INSERT` de
receivable com `updated_at` (coluna NOT NULL).

**0 achados de código na Spec 72.** Fim do ciclo SDD
(roadmap/requirements/design/task `audited`).

## Follow-up (2026-08-13) — fix de flakiness cross-spec no E2E step-up + verificação

**Problema (suíte completa, 2026-08-13):** o `master-console-stepup.spec.js`
falhava apenas na suíte completa/combo auth — o `beforeAll` seedava os masters
OPERATOR/ADMIN sem limpar os deixados por specs anteriores (ex.: `auth.spec`
ativa MFA e deixa vários masters) → o create do teste 4 estourava o limite de 4
perfis (Spec 41) com `409 too_many_masters` (access log: step-up 200 + repeat
409; isolado passa porque o banco está limpo). O round-trip step-up + repetição
da ação sob a suíte completa também excedia o timeout default de 5s no teste 4.

**Correção (somente testes — sem mudança de backend):**
1. `beforeAll` do `master-console-stepup.spec.js`: limpa `master_mfa_methods`
   (V42, cascata p/ `master_totp`/`recovery_codes`), `master_sessions`,
   `master_audit_log` e `masters` antes de seedar OPERATOR/ADMIN (mesmo padrão
   da limpeza do journey 22 — FKs primeiro).
2. Teste 4: timeout explícito de 15s para o round-trip step-up + repetição da
   ação (o spec passa isolado 3×; sob a suíte completa o round-trip excede os 5s).
3. Ajustes correlatos no `merchant-journey.spec.js` (mesma classe de flakiness
   cross-spec): cenário 18 com baseline de `losses` (assere DELTA, não absoluto —
   specs anteriores podem registrar perdas globais) e cenário 22 com `DELETE FROM
   master_mfa_methods` antes do `DELETE FROM masters` (FK — o step-up ativa MFA).

**Verificação (2026-08-13):** suíte E2E completa **122 testes, 0 falhas de
step-up** (Stepup 4 CORRIGIDO). Única falha do run: journey teste 23 (Spec 48 —
race de `updatedAt` da loja, flake pré-existente documentado na auditoria da
Spec 71) — re-executado isolado com banco limpo **27/27** e recorrência
registrada no follow-up da Spec 48 (`specs/48-stores-storefront/task.md`, não
bloqueia).
