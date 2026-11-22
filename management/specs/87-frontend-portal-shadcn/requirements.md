---
name: Frontend — migração Portal para Shadcn UI
status: discarded
references:
  - "management/vision.md#V64"
  - "management/specs/85-frontend-shadcn-foundation/requirements.md"
  - "management/specs/46-portal-frontend/requirements.md"
  - "management/specs/73-frontend-ux/requirements.md"
---

# Spec 87 — Frontend: migração do Portal (Shadcn UI)

## O que entrega

Migração das **11 páginas do Portal do lojista** para os componentes da Spec
85, com **responsividade obrigatória** e funcionalidade preservada. **Execução
antes da Spec 86** (decisão do usuário — portal primeiro: é onde cairão as
telas novas das Specs 76/80/82/84, que já nascem no padrão shadcn). Zero
mudança de contrato HTTP.

Páginas: Dashboard, Charge, Sales, Wallet, Withdraw, Credentials,
Destinations, Webhooks, Login, ForgotPassword, ResetPassword.

## Contexto (por que agora)

A Spec 85 montou a fundação; o portal é a interface do lojista (Spec 46+).
Migrar primeiro o portal garante que as telas novas (bots, broadcast,
tracking, CRM) sejam construídas já no padrão shadcn — sem retrabalho.

## Critérios de aceite

1. **11 páginas migradas** para os componentes shadcn — `styles.css` do
   portal reduzido ao mínimo.
2. **ChargePage** (cobrança PIX + Gift) responsiva: campos empilham no
   mobile; QR/presets adaptam (`grid` → 1 coluna em < `sm`); validação e
   fluxo preservados (Spec 46).
3. **Páginas de credenciais** (Credentials/Destinations/Webhooks): tabelas
   com `overflow-x-auto` (ou cards no mobile); ações (revogar/toggle) com
   `AlertDialog` (Spec 73: "Revogar chave" confirma) e feedback Sonner
   (Spec 73: todas as ações de escrita com sucesso/erro visível).
4. **Responsividade**: formulários empilham (`w-full`), tabelas adaptam,
   nav vira drawer (Spec 85), métricas do Dashboard colapsam em grid.
5. **Acessibilidade da Spec 73 preservada**: banners → Sonner
   (`role=status`/`role=alert`); `ConfirmDialog` → `AlertDialog`; skip-link;
   empty states mantidos.
6. **Login/ForgotPassword/ResetPassword**: fields shadcn, validação e
   fluxos preservados.
7. **E2E `portal-ui.spec.js` verde** com o novo markup (seletores
   atualizados quando necessário — documentado no task).
8. **`npm run build` (tsc) limpo** + suíte E2E existente sem regressão
   (ordem de build: portal antes do console — achado da Spec 73).
9. **Sem mudança de contrato HTTP**.
10. **Dark mode**: portal funciona nos dois temas (Spec 85) — verificação
    por tela-chave (Dashboard, Charge, Sales).

## Dependências

- **Spec 85** (fundação) — componentes/tema/layout.
- **Specs 46/73** — padrão do portal e acessibilidade.
- **Nenhuma de backend**.

## Fora de escopo

- **Migração do console** → Spec 86.
- **Nova funcionalidade / contrato HTTP** — proibido.
- **Telas novas** (bots/broadcast/tracking/CRM) → Specs 76/80/82/84 (serão
  implementadas sobre esta base).
