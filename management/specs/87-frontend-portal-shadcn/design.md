---
name: Frontend — migração Portal para Shadcn UI
status: discarded
---

# Spec 87 — Design: Migração do Portal

## D1 — Conceito

Aplicação da fundação (Spec 85) nas 11 páginas do portal, **executada antes
da migração do console** (decisão do usuário). O portal é onde as Specs
76/80/82/84 vão construir telas novas — migrar primeiro evita retrabalho.

### D1.1 Decisões do usuário (2026-08-12) → regras

| Decisão | Regra |
|---|---|
| Portal primeiro na execução | 85 → 87 → 86 (apesar da numeração) |
| Funcionalidade preservada | cobrança/credenciais/webhooks/destinos/saque intactos |
| Responsividade obrigatória | Charge e formulários empilham; tabelas adaptam |
| Feedback Spec 73 | Sonner (role) + AlertDialog para ações destrutivas |

## D2 — Padrão de migração por página

- Mesmo padrão da Spec 86 (D2): `Card`/`Table`/`Badge`/`Tabs`/`Input`/
  `Select`/`Button`; tabelas com `overflow-x-auto`; grids colapsam; forms
  `w-full` no mobile; empty states + `Skeleton`/`Spinner`.
- `ConfirmDialog` (portal) → `AlertDialog` (Spec 85) — "Revogar chave"
  continua confirmando (Spec 73).
- Feedback: banners → Sonner com `role=status`/`role=alert` (Spec 73 exige
  toda ação de escrita com sucesso/erro visível).

## D3 — Páginas (agrupamento)

- **F0 Comercial**: DashboardPage (métricas + série + últimas vendas),
  SalesPage (tabela + filtros), ChargePage (PIX + Gift — QR/presets
  responsivos: `grid-cols-1 sm:grid-cols-2`).
- **F1 Financeiro**: WalletPage, WithdrawPage (card de saque + auto-withdraw,
  Spec 70).
- **F2 Credenciais**: CredentialsPage, DestinationsPage, WebhooksPage
  (tabelas + AlertDialog para revogar/toggle).
- **F3 Auth**: LoginPage, ForgotPasswordPage, ResetPasswordPage (fields
  shadcn, fluxos preservados).

## D4 — Testes

- E2E `portal-ui.spec.js` verde (seletores atualizados quando necessário,
  documentado no task).
- Smoke visual nos 2 temas (Dashboard/Charge/Sales).
- `npm run build` (tsc) limpo; suíte E2E completa sem regressão (ordem de
  build: portal antes do console — achado da Spec 73).
