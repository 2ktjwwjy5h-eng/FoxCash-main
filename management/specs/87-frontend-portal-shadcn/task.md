---
name: Frontend — migração Portal para Shadcn UI
status: discarded
---

# Spec 87 — Task: Migração do Portal

## F0 — Comercial (critérios 1–2, 4)

- [ ] T0.1: `DashboardPage` — métricas em grid responsivo + série + últimas
      vendas (shadcn).
- [ ] T0.2: `SalesPage` — `Table` com `overflow-x-auto` + filtros.
- [ ] T0.3: `ChargePage` — PIX + Gift responsivos (QR/presets
      `grid-cols-1 sm:grid-cols-2`); validação/fluxo preservados (Spec 46);
      feedback Sonner.

## F1 — Financeiro (critérios 3–5)

- [ ] T1.1: `WalletPage` — saldo/movimentações com `Table`/`Card`.
- [ ] T1.2: `WithdrawPage` — formulário + card de auto-withdraw (Spec 70)
      empilhando no mobile.

## F2 — Credenciais (critérios 3, 5)

- [ ] T2.1: `CredentialsPage`, `DestinationsPage`, `WebhooksPage` — tabelas
      shadcn + `AlertDialog` para revogar/toggle (Spec 73: confirmação +
      feedback Sonner em toda ação).
- [ ] T2.2: Empty states + loading `Skeleton`/`Spinner` (Spec 85).

## F3 — Auth + fechamento (critérios 6–10)

- [ ] T3.1: `LoginPage`, `ForgotPasswordPage`, `ResetPasswordPage` — fields
      shadcn, fluxos preservados.
- [ ] T3.2: `styles.css` do portal reduzido (remover utilitários migrados).
- [ ] T3.3: E2E `portal-ui.spec.js` verde (seletores atualizados
      documentados).
- [ ] T3.4: Smoke visual nos 2 temas (Dashboard/Charge/Sales).
- [ ] T3.5: `npm run build` (tsc) limpo + suíte E2E completa sem regressão
      (ordem: portal antes do console).
- [ ] T3.6: `/spec-verify 87` e depois `/spec-audit 87` (critérios 1–10).
