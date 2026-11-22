---
name: Frontend — migração Master Console para Shadcn UI
status: discarded
references:
  - "management/vision.md#V63"
  - "management/specs/85-frontend-shadcn-foundation/requirements.md"
  - "management/specs/71-master-console/requirements.md"
  - "management/specs/72-master-console-stepup/requirements.md"
  - "management/specs/73-frontend-ux/requirements.md"
  - "management/specs/69-rbac-master-stepup/requirements.md"
---

# Spec 86 — Frontend: migração do Master Console (Shadcn UI)

## O que entrega

Migração das **14 páginas do Master Console** para os componentes da Spec 85
(fundação Shadcn UI), com **responsividade obrigatória** e **funcionalidade
preservada** (step-up TOTP, RBAC-aware, autogestão de MFA — Specs 69/71/72).
Zero mudança de contrato HTTP.

Páginas: Dashboard, Transactions, Merchants, MerchantDetail, Withdrawals,
Financial, Receivables, Audit, Activity, Reconciliation, Lookup, Security,
Profiles, Login.

## Contexto (por que agora)

A Spec 85 montou a fundação; esta spec aplica no console. O console é a
interface do operador (Specs 71/72) — a migração preserva os fluxos
sensíveis (step-up com TOTP) e a acessibilidade da Spec 73, agora com
componentes nativos do Radix.

## Critérios de aceite

1. **14 páginas migradas** para os componentes shadcn (Card/Table/Badge/
   Tabs/Input/Select/Button etc. da Spec 85) — `styles.css` do console
   reduzido ao mínimo (utilitários residuais removidos ao final).
2. **Step-up TOTP preservado** (Spec 72): fluxo 403 `step_up_required` →
   `StepUpPrompt` (agora shadcn `Dialog` da Spec 85) → `POST /mfa/step-up` →
   repete a ação; janela 15 min; nunca 401.
3. **RBAC-aware preservado** (Spec 69): navegação e ações por permissão
   (`permissions[]`); o backend continua a autoridade (403 `forbidden`).
4. **Responsividade**: grids de métricas colapsam (`grid-cols-1 sm:grid-cols-2
   xl:grid-cols-4/5`); tabelas com wrapper `overflow-x-auto` (ou viram cards
   em < `sm` quando faz sentido — decisão por tela documentada no task);
   formulários empilham no mobile; nav vira drawer (Spec 85).
5. **Acessibilidade da Spec 73 preservada**: `ConfirmDialog` (AlertDialog) e
   `StepUpPrompt` (Dialog) com foco/Escape/aria nativos; banners → Sonner com
   `role=status`/`role=alert`; skip-link (Spec 85); empty states mantidos.
6. **Login/MFA**: `LoginPage` migrada (fields shadcn, validação preservada,
   fluxo TOTP quando ativo).
7. **E2E verdes**: `master-console-ui.spec.js` e `master-console-stepup.spec.js`
   passam com o novo markup (seletores atualizados quando necessário —
   **documentado no task**, nunca mudando o comportamento).
8. **`npm run build` (tsc) limpo** + suíte E2E existente sem regressão
   (ordem de build: portal antes do console — achado da Spec 73).
9. **Sem mudança de contrato HTTP** — nenhuma rota/payload alterada.
10. **Dark mode**: console funciona nos dois temas (Spec 85) — verificação
    visual por tela-chave (Dashboard, Merchants, Financial).

## Dependências

- **Spec 85** (fundação) — componentes/tema/layout.
- **Specs 69/71/72/73** — RBAC, step-up, acessibilidade (contrato de
  comportamento).
- **Nenhuma de backend**.

## Fora de escopo

- **Migração do portal** → Spec 87.
- **Nova funcionalidade / contrato HTTP** — proibido.
- **Customização de marca** (paleta própria) — evolução.
