---
name: Gateways — UI do portal
status: audited
---

# Spec 108 — Design: Gateways (UI)

## D1 — Conceito

`GatewaysPage.tsx` nova (rota `/gateways`) consumindo a Spec 107. Layout em
seções: cards (overview), fila de fallback, catálogo.

```text
portal/src/pages/GatewaysPage.tsx   → nova página
portal/src/components/GatewayCard.tsx
portal/src/components/FallbackChain.tsx → Principal → Fallback1 → Fallback2
portal/src/components/GatewayCatalog.tsx
```

## D2 — Layout

`[Cards dos adquirentes] / [FallbackChain] / [GatewayCatalog (seções Pix,
Cartão, Cripto)]`.

- **FallbackChain**: renderiza os providers ordenados com setas; badge
  "Principal" no 1º; texto "Rotação automática em falha (circuit breaker —
  Spec 91)"; estado OPEN → badge vermelho.
- **Catálogo**: agrupado por `category`; card com `name` + badges
  `available`/`configured`.

## D3 — Testes

- `npm run build` (tsc) limpo no portal.
- E2E `portal-gateways-ui.spec.js`: cards, ordem do fallback, catálogo com
  seções, empty state, 401.

## D3.1 — Não ignorar a Spec 139 (fallback por perfil)

A ordem do fallback reflete a **rota do perfil ativo** (Spec 122/124); perfil
`BOT_ONLY` mostra "rota por risco" (sem override — Spec 139). A UI deve
respeitar a lente Bot/Gateway do `ProfileSelector` (Spec 139).

## D4 — Wireframe da página de gateways (registro de design 2026-08-16)

Visual de referência (NOTA: esta é a página **administrativa** do lojista sobre
os adquirentes — distinta da página pública de checkout do Gateway, Specs
115/116, e dos bots, Specs 48–52; ver ADR-020).

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Gateways                                                                │
│                                                                          │
│  Ordem de fallback automática — rotação em caso de falha:                 │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐                │
│  │   Pagar.me   │ ─▶ │    Asaas     │ ─▶ │  Simulador   │                │
│  │  ● PRINCIPAL │    │  ○ FALLBACK 1│    │  ○ FALLBACK 2│                │
│  │   circuito:  │    │   circuito:  │    │   circuito:  │                │
│  │   CLOSED ✓   │    │   CLOSED ✓   │    │   OPEN ✗     │                │
│  └──────────────┘    └──────────────┘    └──────────────┘                │
│    "rotação automática em falha (circuit breaker — Spec 91)"              │
│                                                                          │
│  ── Adquirentes conectados · extrato do mês ──                           │
│  ┌────────────────────────────────────────────────────┐                  │
│  │  Pagar.me   [Principal] [● CLOSED]  R$ 293,58 · 42 │                  │
│  │  Asaas      [Fallback 1] [● CLOSED] R$    0,00 · 0 │                  │
│  │  Simulador  [Fallback 2] [✗ OPEN]   R$    0,00 · 0 │                  │
│  └────────────────────────────────────────────────────┘                  │
│                                                                          │
│  ── Catálogo de gateways disponíveis ──                                  │
│  ┌─ PIX ─────────────────┐ ┌─ CARTÃO ───────────────┐                    │
│  │ Pagar.me  [Conectado] │ │ Pagar.me   [Em breve]  │                    │
│  │ Asaas     [Disponível]│ │ Cielo      [Em breve]  │                    │
│  │ Simulador [Conectado] │ └────────────────────────┘                    │
│  └───────────────────────┘ ┌─ CRIPTO ───────────────┐                    │
│                            │ BitPay     [Em breve]  │                    │
│                            └────────────────────────┘                    │
└──────────────────────────────────────────────────────────────────────────┘
```

Notas de implementação:
- **FallbackChain** (D2): fila horizontal Principal → Fallback 1 → Fallback 2
  com setas "cai para" + texto "rotação automática em falha"; badge CLOSED/OPEN/
  HALF_OPEN do `PixProviderHealthRegistry` (Spec 91).
- **Cards** (`GatewayCard`): `displayName`, badge de ordem, `circuitState`,
  `volumePeriod`/`countPeriod` (Σ/COUNT APPROVED no mês — reuso Spec 105).
- **Catálogo** (`GatewayCatalog`): seções PIX/CARTÃO/CRIPTO; badges
  `available`/`configured` — **read-only** (ativação = evolução, mudaria o
  router da Spec 91).
- Empty state: "Nenhum adquirente configurado" quando `providers` vazio.
