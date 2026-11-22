---
name: Gateways — visão dos adquirentes, fallback e catálogo (backend)
status: audited
---

# Spec 107 — Design: Gateways (backend)

## D1 — Conceito

Um **`PortalGatewayService`** (aplicação) que expõe ao portal o estado do
roteamento da Spec 91 (order/estado/extrato) e o catálogo — **sem expor
segredos** e sem tocar no roteamento.

```text
PortalGatewayController (REST, ROLE_MERCHANT_PORTAL)
   └─ PortalGatewayService
        ├─ RoutingPolicyResolver (Spec 91) — order por policy do lojista
        ├─ PixProviderHealthRegistry (Spec 91) — circuitState
        └─ TransactionRepository — extrato por provider (Σ/COUNT APPROVED)
```

## D2 — Endpoints

### D2.1 `GET /api/v1/portal/gateways`

`{providers: [{provider, displayName, order, circuitState, volumePeriod,
countPeriod}]}`.

- **order**: da policy do lojista (`RoutingPolicyResolver.resolve(merchantId)`);
  providers fora da policy → não listados (ou `order: null` — decisão:
  não listados, KISS).
- **circuitState**: `PixProviderHealthRegistry.state(provider)` (CLOSED/OPEN/
  HALF_OPEN — Spec 91); ausente → CLOSED.
- **extrato**: `pix_transactions.provider` = provider, status APPROVED, mês
  corrente → Σ/COUNT.
- **displayName**: mapa `pagarme→"Pagar.me"`, `asaas→"Asaas"`, `mock→
  "Simulador"`, `suitpay→"Suitpay"`.

### D2.2 `GET /api/v1/portal/gateways/catalog`

`[{id, name, category (PIX|CARTÃO|CRIPTO), available, configured}]`.

- **Base**: adapters reais da Spec 91 (`pagarme`, `asaas`) + `mock` (dev) +
  entradas de catálogo para CARTÃO/CRIPTO com `available: false` (sem adapter).
- **configured**: provider presente no `RoutingProperties` (Spec 91).

## D3 — Persistência

Nenhuma migration (dados existentes).

## D4 — Testes

**Unitários:** `PortalGatewayServiceTest` (order da policy, circuitState
ausente → CLOSED, extrato por provider, catálogo available/configured).
**Integração:** controller 200/401/403. **E2E:** `portal-gateways-ui.spec.js` —
lista de providers + catálogo.

## D5 — Não ignorar a Spec 139 (escopo do perfil)

A rota de cobrança é **por perfil de operação** (Spec 122 — `gateway_route` +
`scope` V76, Spec 139). A visão de fallback/ordem deve refletir o **perfil
ativo** (`X-Profile-ID`): resolver via `RoutingPolicyResolver.resolveProviders(
merchantId, profileId)` (Spec 122/139). Perfil `BOT_ONLY` **nunca** aplica
override — rota por risco (defesa Spec 139); o catálogo (`available`/
`configured`) permanece global.
