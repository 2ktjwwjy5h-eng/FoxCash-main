---
name: Gateways — UI do portal
status: audited
references:
  - "management/vision.md#V80"
  - "management/specs/107-gateways-backend/requirements.md"
  - "management/specs/73-frontend-ux/requirements.md"
---

# Spec 108 — Gateways: UI do portal

## O que entrega

Página **Gateways** no portal (rota `/gateways`) com a descrição de produto do
usuário (2026-08-15). Puramente apresentação sobre os endpoints da Spec 107.
Padrão UX da Spec 73.

1. **Visão geral dos adquirentes**: cards dos gateways conectados (Pagar.me →
   exibido como "Pagar.me"; os nomes de referência "Shark Wallet/Atenas Pay"
   são do sistema de referência — mapeamento opcional na UI) com extrato
   (volume/contagem do período) e status.
2. **Sistema de fallback inteligente**: organização visual da ordem de
   prioridade — Principal e Fallbacks 1/2 — com o estado do circuito (ROTACIONA
   em falha), detalhando a rotação automática.
3. **Catálogo de gateways disponíveis**: grade de integrações divididas por
   Pix/Cartão/Cripto, com `available`/`configured` (read-only; ativação real é
   evolução).

## Contexto (por que agora)

Descrição de produto do usuário (2026-08-15) — detalhamento de Gateways. O
backend da Spec 107 (criada junto) expõe os dados.

## Critérios de aceite

1. **Cards dos adquirentes**: por provider — `displayName`, `order` (badge
   "Principal"/"Fallback 1"/"Fallback 2"), `circuitState` (badge CLOSED/OPEN/
   HALF_OPEN), `volumePeriod`/`countPeriod`. Sem provider → empty state.
2. **Visual do fallback**: fila horizontal Principal → Fallback 1 → Fallback 2
   com setas "cai para" e texto "rotação automática em falha" (do backend Spec
   91 — a UI explica).
3. **Catálogo**: seções Pix/Cartão/Cripto; card com `name`, `available` (badge
   "Disponível"/"Em breve") e `configured` (badge "Conectado"/"Não conectado").
   **Read-only** (sem botão de ativação — evolução).
4. **UX**: Spinner, empty states, `ErrorBanner` (Spec 73).
5. **Rotas/SPA**: `/gateways` no `PortalSpaController` + `spaPublicPaths()`
   (lição Specs 80/84).
6. **Testes**: `npm run build` (tsc) limpo no portal + E2E
   (`portal-gateways-ui.spec.js`) — cards + ordem do fallback + catálogo;
   suíte completa sem regressão.

## Dependências

- **Spec 107** (backend) — endpoints.
- **Spec 73** — UX.
- **Spec 139** (escopo + lente, `in-progress` 2026-08-16) — o fallback visual
  reflete a **rota do perfil ativo** (Spec 122/124); perfil `BOT_ONLY` mostra
  "rota por risco" (sem override — Spec 139). Respeitar a lente Bot/Gateway.

## Fora de escopo

- **Ativação/configuração de adquirentes** — evolução (mudaria o router Spec 91).
- **Backend** — Spec 107.
