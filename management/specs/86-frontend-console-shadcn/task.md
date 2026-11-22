---
name: Frontend — migração Master Console para Shadcn UI
status: discarded
---

# Spec 86 — Task: Migração do Master Console

## F0 — Leitura (critérios 1, 4–6)

- [ ] T0.1: `DashboardPage` — métricas em grid responsivo + tabelas shadcn.
- [ ] T0.2: `TransactionsPage`, `LookupPage`, `ReconciliationPage`,
      `ActivityPage`, `AuditPage` — `Table` shadcn com `overflow-x-auto` +
      `Badge` de status.
- [ ] T0.3: Empty states → shadcn; loading → `Skeleton`/`Spinner` (Spec 85).

## F1 — Detalhe/lojista + ações OPERATOR (critérios 2–4)

- [ ] T1.1: `MerchantsPage` — tabela + badges de status/risco.
- [ ] T1.2: `MerchantDetailPage` — `Tabs` shadcn (ledger/receivables/
      withdrawals) + ações reversíveis (suspender/liberar, bloquear/liberar)
      com `AlertDialog` (`tone` danger/primary).
- [ ] T1.3: `ReceivablesPage`, `WithdrawalsPage` — tabelas + ações com
      confirmação.
- [ ] T1.4: Grids de métricas responsivos (`grid-cols-1 sm:grid-cols-2
      xl:grid-cols-N`).

## F2 — Financeiro/segurança/login (critérios 2, 5–6)

- [ ] T2.1: `FinancialPage` — ledger + `Field` (Spec 85) com `required`
      preservado.
- [ ] T2.2: `SecurityPage` — autogestão de MFA (activate/confirm/deactivate/
      recovery) no novo markup.
- [ ] T2.3: `ProfilesPage` — gestão de perfis (função admin, step-up) com
      `AlertDialog`/`Dialog`.
- [ ] T2.4: `LoginPage` — fields shadcn + fluxo TOTP quando ativo.
- [ ] T2.5: Step-up: `StepUpPrompt` (Dialog Spec 85) — 403 → prompt → repete;
      nunca 401; janela 15 min (Spec 72).

## F3 — Fechamento (critérios 7–10)

- [ ] T3.1: `styles.css` do console reduzido (remover utilitários migrados;
      manter só o residual documentado).
- [ ] T3.2: E2E `master-console-ui` + `master-console-stepup` verdes
      (seletores atualizados documentados no task).
- [ ] T3.3: Smoke visual nos 2 temas (Dashboard/Merchants/Financial).
- [ ] T3.4: `npm run build` (tsc) limpo + suíte E2E completa sem regressão
      (ordem: portal antes do console).
- [ ] T3.5: `/spec-verify 86` e depois `/spec-audit 86` (critérios 1–10).
