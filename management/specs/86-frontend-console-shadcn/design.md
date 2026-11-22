---
name: Frontend — migração Master Console para Shadcn UI
status: discarded
---

# Spec 86 — Design: Migração do Master Console

## D1 — Conceito

Aplicação da fundação (Spec 85) nas 14 páginas do console: troca de markup
custom por componentes shadcn, **preservando o contrato de comportamento**
das Specs 71/72/73 (step-up, RBAC, acessibilidade). A ordem das páginas segue
a criticidade dos fluxos (ações com step-up por último, depois de validar as
de leitura).

### D1.1 Decisões do usuário (2026-08-12) → regras

| Decisão | Regra |
|---|---|
| Funcionalidade preservada | step-up/RBAC/MFA intactos (Specs 69/71/72) |
| Responsividade obrigatória | grids colapsam; tabelas com scroll/cards; forms empilham |
| Acessibilidade Spec 73 | AlertDialog/Dialog nativos; Sonner com role; skip-link |
| Zero contrato HTTP | apresentação apenas |

## D2 — Padrão de migração por página

1. Substituir classes custom por componentes shadcn (`Card`, `Table`,
   `Badge`, `Tabs`, `Input`, `Select`, `Button` com variantes).
2. Tabelas: wrapper `<div className="overflow-x-auto">` (scroll horizontal)
   ou layout em cards em < `sm` para as principais (Merchants, Transactions,
   Financial, Receivables) — decisão por tela no task.
3. Métricas: `Card` em `grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-N`.
4. Formulários: `Field` (Spec 85) empilhando no mobile (`w-full`).
5. Empty states e loading: `Skeleton`/`Spinner` (Spec 85) — padrão Spec 73.

## D3 — Fluxos sensíveis (não quebrar)

- **Step-up** (`StepUpPrompt` → Dialog Spec 85): sequência 403 →
  prompt TOTP → `POST /api/v1/master/mfa/step-up` → repete ação; janela
  15 min respeitada (cache `stepped_up_at` no cliente); nunca 401.
- **RBAC-aware**: navegação/menu por `permissions[]` (Spec 85 D4 usa a mesma
  lista); ações sem permissão escondidas; backend decide (403).
- **ConfirmDialog**: trocas para `AlertDialog` (Spec 85 T3.3) com `tone`
  (danger/primary) — ações destrutivas (suspender/revogar/write-off)
  continuam confirmando.
- **Autogestão de MFA** (SecurityPage): fluxo activate/confirm/deactivate/
  recovery preservado no novo markup.

## D4 — Páginas (agrupamento)

- **F0 Leitura**: Dashboard, Transactions, Lookup, Reconciliation, Activity,
  Audit (tabelas + métricas).
- **F1 Detalhe/lojista**: Merchants, MerchantDetail, Receivables, Withdrawals
  (tabs + tabelas + ações reversíveis de OPERATOR com ConfirmDialog).
- **F2 Financeiro/segurança**: Financial (ledger), Security (MFA), Profiles
  (gestão com step-up), Login (auth + TOTP).
- Cada página: mesma URL, mesmos dados, novo markup.

## D5 — Testes

- E2E `master-console-ui.spec.js` (4) + `master-console-stepup.spec.js` (5):
  verdes — seletores atualizados quando o novo markup exigir (documentado).
- Smoke visual nos 2 temas (Dashboard/Merchants/Financial).
- `npm run build` (tsc) limpo; suíte E2E completa sem regressão.
