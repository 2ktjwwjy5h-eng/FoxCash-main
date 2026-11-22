---
name: Master Console — frontend web do perfil master (fatia 2)
status: audited
---

# Spec 72 — Design: Master Console (fatia 2)

## D1 — Conceito

A fatia 2 é a **camada de execução sensível** do Master Console. O backend
(Specs 69/32/33/34/41) já protege as 4 ações sensíveis com **permissão +
step-up** — esta spec entrega:

1. **Espelho `GET /api/v1/master/invoices`** (único backend aditivo — D2);
2. **Step-up TOTP no cliente** — componente reutilizável que intercepta o 403
   `step_up_required`, pede o código, chama o step-up e **repete a ação**
   (D3);
3. **4 telas/ações sensíveis** — saque administrativo (D4.1), write-off
   (D4.2), cobrança manual de fatura (D4.3) e gestão de perfis master (D4.4).

Princípio da fatia 1 (Spec 71) mantido: **UI RBAC-aware esconde, nunca
autoriza; o backend é a autoridade** (403 `forbidden`/`step_up_required`).

### D1.1 Decisões do usuário (2026-08-11) → regras

| Decisão | Regra |
|---|---|
| Fatia 2 = ações sensíveis + perfis | Saque administrativo, write-off, cobrança manual, gestão de perfis — com step-up TOTP |
| Step-up no cliente (403 → prompt → repete) | O console chama `POST /master/mfa/step-up` e repete a ação; **nunca 401** (a sessão não cai) |
| Janela de step-up 15 min por sessão | Cliente cacheia para UX; **backend é a fonte de verdade** (se expirou, 403 de novo) |
| Perfis master admin-only | `master.profiles.manage` (Spec 69 — `can_manage_masters`); UI esconde, backend decide |

## D2 — Backend aditivo: `GET /api/v1/master/invoices` (critério 1)

**Novo endpoint no `MasterReadController`** (Spec 71, base `/api/v1/master`) —
espelho **read-only** do `GET /api/v1/admin/invoices` (Spec 8). Reusa
`AdminDashboardUseCase.searchInvoices` (o use case não tem role embutida — a
fronteira de role é o controller; padrão exato dos espelhos da Spec 71).

```java
@GetMapping("/invoices")
public PageResponse<InvoiceResponse> invoices(
        @RequestParam(required = false) String merchantId,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String billingPeriod,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
    InvoiceSearchQuery query = new InvoiceSearchQuery(
            QueryParamParser.parseUuid(merchantId, "merchantId"),
            QueryParamParser.parseEnum(InvoiceStatus.class, status, "status"),
            QueryParamParser.parsePeriod(billingPeriod),
            QueryParamParser.clampPage(page),
            QueryParamParser.clampSize(size));
    return PageResponse.from(adminDashboard.searchInvoices(query), InvoiceResponse::from);
}
```

- **Mesmo shape/validações** do admin (filtros `merchantId`/`status`/
  `billingPeriod`, paginação, cap 100, 400 `invalid_argument` via
  `IllegalArgumentException` → `GlobalExceptionHandler`).
- `QueryParamParser.parsePeriod` já existe (Spec 8, usado pelo admin).
- **Contrato `/api/v1/admin/invoices` inalterado.**
- Testes: `MasterReadControllerTest` ganha 2-3 casos (delegation com filtros
  parseados, 400 em `billingPeriod`/`status` inválidos, clamp size 100).

## D3 — Step-up TOTP no cliente (critérios 2, 8)

### D3.1 Interceptor + prompt

O `apiClient.ts` (Spec 71) já propaga **403/409 como `ApiError`** (nunca
logout). Adiciona-se o **reconhecimento do 403 `step_up_required`**:

```ts
// apiClient.ts — ApiError ganha o flag
export class ApiError extends Error {
  readonly code?: string;
  readonly status: number;
  readonly stepUpRequired: boolean; // status === 403 && body.code === 'step_up_required'
}
```

### D3.2 Hook `useStepUp` (componente `StepUpPrompt`)

```ts
// hooks/useStepUp.tsx
export function useStepUp(): {
  /** Envolve uma ação sensível: tenta; em 403 step_up_required abre o prompt
   *  TOTP, chama o step-up e REPETE a ação (1 tentativa). */
  run: <T>(action: () => Promise<T>) => Promise<T>;
  /** Instante do último step-up bem-sucedido (janela 15 min — UX). */
  steppedUpAt: Instant | null;
  /** True quando o prompt TOTP está aberto. */
  promptOpen: boolean;
}
```

Fluxo:

```
run(action)
  → try { return await action(); }
  → catch (ApiError e)
      se e.stepUpRequired:
        steppedUpAt válido (janela 15 min)? → repete action() (fail-open: o
          backend ainda valida — se expirou de verdade, 403 de novo → prompt)
        senão → abre StepUpPrompt (modal TOTP)
          → POST /api/v1/master/mfa/step-up {code} (Authorization Bearer)
            → 200: grava steppedUpAt = now → repete action()
            → 401/400/429: mostra erro no modal (código errado/rate limit)
              e mantém aberto para nova tentativa
      senão → rethrow (403 forbidden = "sem permissão" → banner da página)
```

- **Nunca logout/redirect** em 403 (o `apiClient` já só desloga em 401 —
  Spec 71/D7.4).
- **Fail-closed do cache**: o `steppedUpAt` só evita o *prompt*, nunca decide a
  autorização — a ação é sempre repetida e o 403 do backend é a verdade.
- **Janela**: `MasterStepUpProperties.window` default 15 min (Spec 69) — o
  cliente usa a mesma constante (sem import — espelho de `15 * 60 * 1000` ms,
  documentado; a janela real é do backend).

### D3.3 Componente `StepUpPrompt`

Modal (padrão `ConfirmDialog` da fatia 1) com: título "Reautenticação
necessária", explicação ("Ação sensível exige confirmação com o app de
autenticação"), campo de código TOTP (autofocus, submit com Enter), botões
Confirmar/Cancelar; erro do backend (`ApiError.message`); **não** exibe
secret/oráculo. Cancelar → `run()` rejeita com `ApiError(403,
step_up_required)` (a página mostra banner neutro).

## D4 — Telas/ações da fatia 2 (critérios 3–7)

### D4.1 Saque administrativo (Spec 32 — `withdrawal.administrative`)

**Onde:** página Financeiro (`/console/financial`) — seção "Saque
administrativo" (além do resumo de receita) **e** botão no detalhe do lojista
(`MerchantDetailPage`, seção "Ações de operação").

**Formulário** (`AdministrativeWithdrawalRequest`):
- `merchantId` (select de lojistas — `GET /master/merchants` da fatia 1);
- `amount` (número decimal, > 0);
- `destinationKey` (texto — chave Pix do destino; máscara na resposta);
- `reason` (texto obrigatório).

**Fluxo:** `RequirePermission("withdrawal.administrative")` → botão →
`ConfirmDialog` (resumo da ação + valores) → `useStepUp.run(() =>
api.post('/api/v1/master/merchants/{id}/withdrawals/administrative', {...}))`
→ 201 `{...}` → feedback com saldo pós-débito (`walletBalance`); 409
`insufficient_balance`/`merchant_not_active` → `ErrorBanner`; 403
`step_up_required` → prompt TOTP → repete (D3.2).

### D4.2 Write-off de receivable (Spec 34 — `receivable.write_off`)

**Onde:** `ReceivablesPage` (`/console/receivables`).

**Fluxo:** linha do receivable (origem `CHARGEBACK`/`REFUND`/`INVOICE` com
status `OPEN`/`PARTIAL`) → botão "Write-off" →
`RequirePermission("receivable.write_off")` → `ConfirmDialog` com campo
`reason` obrigatório → `useStepUp.run(() => api.post('/api/v1/master/
receivables/{id}/write-off', { reason }))` → 200 `{loss}` → feedback
("Baixa de R$ X registrada") + recarregar lista; 400 `reason_required` →
erro; 403 `step_up_required` → prompt (D3.2).

### D4.3 Cobrança manual de fatura (Spec 33 — `invoice.collect`)

**Onde:** `MerchantDetailPage` — nova tab/aba "Faturas" (além de earnings/fees/
risco/ledger/receivables/saques).

**Painel:** `GET /master/invoices?merchantId={id}` (D2) → tabela de faturas
(período, valor total, status, vencimento); em faturas `PENDING`/`OVERDUE` →
botão "Cobrar" → `RequirePermission("invoice.collect")` → `ConfirmDialog`
("Cobrar a fatura do período X?") → `useStepUp.run(() => api.post(
'/api/v1/master/merchants/{id}/invoices/{invoiceId}/collect'))` → 200
`{debit, remaining}` → feedback ("Debitado R$ X · saldo devedor R$ Y") +
recarregar; 403 `step_up_required` → prompt (D3.2).

### D4.4 Gestão de perfis master (Spec 41/69 — `master.profiles.manage`)

**Onde:** nova página `/console/profiles` ("Perfis master"). Item no
`NavigationMap` com `permission: 'master.profiles.manage'` (só ADMIN vê —
Spec 69; fatia 1 já tem o padrão comentado no `Layout.tsx`).

**Lista:** `GET /api/v1/master/profiles` → tabela (username, nome, ativo,
`canManageMasters`, criado em). **Leitura: exige admin mas SEM step-up**
(`MasterAdminGuard.requireAdmin` — verificado no `MasterProfileService.list`).

**Criar:** `POST /api/v1/master/profiles` `{username, name}` → 201
`MasterCreatedProfileResponse` com `temporaryPassword` — **exibida 1×** em
modal com aviso ("Guarde esta senha — não será exibida novamente") +
botão "Já copiei"; fechar o modal descarta a senha. **Exige step-up.**

**Ativar/Desativar:** `POST /api/v1/master/profiles/{id}/activate|deactivate`
→ 204 → recarregar lista (nunca desativar a si mesmo — o backend já protege
"último admin ativo", Spec 41). **Exigem step-up.**

**Criar/ativar/desativar** com `RequirePermission` + `useStepUp.run` (403
`step_up_required` → prompt → repete). **403 `forbidden`** em master não-admin
→ banner "sem permissão" (sessão permanece — E2E verifica). A **listagem** não
usa `useStepUp` (é leitura).

## D5 — Frontend: arquivos (espelho da fatia 1)

```
console/src/
  apiClient.ts               [+ ApiError.stepUpRequired]
  hooks/useStepUp.tsx        [NOVO — D3.2]
  components/StepUpPrompt.tsx [NOVO — D3.3]
  components/Layout.tsx      [+ item "Perfis master" (permission)]
  pages/FinancialPage.tsx    [+ seção "Saque administrativo" — D4.1]
  pages/MerchantDetailPage.tsx [+ aba "Faturas" + botão saque admin — D4.1/D4.3]
  pages/ReceivablesPage.tsx  [+ botão write-off — D4.2]
  pages/ProfilesPage.tsx     [NOVO — D4.4]
  App.tsx                    [+ rota /console/profiles]
  types.ts                   [+ Invoice, MasterProfile, CollectionResult...]
```

## D6 — E2E browser (`master-console-stepup.spec.js`)

Playwright, `test.describe.serial`, padrão `master-console-ui.spec.js` +
`stepup.spec.js` (Spec 69). Requer a app com `NYXCASH_CONSOLE_SERVE_FRONTEND`
(CI já tem). Seed via SQL (BCrypt fixo + MFA ativado via API com `totpAt`).

Cenários (critério 10):

1. **Ação sensível sem step-up → prompt TOTP → repete → 200**: login no
   browser → ativar MFA via API (`POST /master/mfa/activate` +
   `confirm` com `totpAt`) → abrir Receivables → click "Write-off" (sem
   step-up) → 403 `step_up_required` → prompt TOTP aparece → digitar
   `totpAt(secret)` → confirmar → ação repetida → 200 `{loss}` → feedback.
2. **Sessão permanece viva após 403**: antes do prompt, o 403 `step_up_required`
   não desloga — navegação continua (dashboard renderiza; `/master/me` 200).
3. **Janela expirada → prompt reabre**: com `stepped_up_at` no passado (via
   SQL `UPDATE master_sessions SET stepped_up_at = now() - interval '20 min'`),
   nova ação sensível → 403 → prompt reabre (backend é a fonte de verdade).
4. **Perfis master admin-only**: master OPERATOR (não-admin) → item "Perfis
   master" **escondido** no menu; `GET /master/profiles` via API → 403
   `forbidden` → sessão viva (me 200). Master ADMIN → tela visível; criar
   perfil → 201 com `temporaryPassword` exibida (1×); desativar → 204.
5. **Saque administrativo com step-up**: Financial → preencher formulário
   (merchantId/amount/destinationKey/reason) → confirmar → 403 → prompt → TOTP
   → repetido → 201 com saldo pós-débito.

## D7 — Decisões de design (com racional)

| # | Decisão | Racional |
|---|---|---|
| D7.1 | `run(action)` com retry automático (1 tentativa pós-step-up) | UX mínima: o usuário digita o TOTP e a ação executa sozinha; se o backend rejeitar de novo (janela real expirada), o 403 reaparece e o prompt reabre (não loop infinito) |
| D7.2 | Cache do `steppedUpAt` (15 min) é só UX | A janela real é do backend (`stepped_up_at` na sessão — Spec 69); o cache evita o prompt em ações sequenciais mas NUNCA autoriza sozinho (fail-closed) |
| D7.3 | `ApiError.stepUpRequired` derivado de `status===403 && code==='step_up_required'` | Reuso do contrato existente da Spec 69 (GlobalExceptionHandler) — sem endpoint novo para o cliente |
| D7.4 | Espelho de invoices no `MasterReadController` (não controller novo) | Reuso do padrão da fatia 1; o use case `searchInvoices` não tem role — a fronteira é o controller |
| D7.5 | Perfis master numa página nova com item de menu permissionado | `master.profiles.manage` é a única permissão admin-only da Spec 69 — a UI filtra por ela; fatia 1 já deixou o item comentado |
| D7.6 | Write-off/collect/saque admin em telas existentes (não páginas novas) | São ações pontuais sobre dados já exibidos (receivables/faturas/lojistas) — KISS, sem nova navegação |

## D8 — Rastreabilidade (critérios ↔ design ↔ task)

| Critério (requirements) | Design | Task |
|---|---|---|
| 1 (espelho invoices) | D2 | F0 |
| 2 (step-up no cliente) | D3 | F1 |
| 3 (saque administrativo) | D4.1 | F2 |
| 4 (write-off) | D4.2 | F2 |
| 5 (cobrança manual) | D4.3 | F2 |
| 6 (gestão de perfis) | D4.4 | F2 |
| 7 (UI RBAC-aware) | D1/D5 | F2 |
| 8 (janela de step-up) | D3.2/D7.2 | F1 |
| 9 (MFA autogestão permanece) | — (Spec 71) | — |
| 10 (E2E browser) | D6 | F3 |
| 11 (sem regressão) | D2 | F0, F3 |
| 12 (dependências) | D1/D2/D4 | F0–F2 |
