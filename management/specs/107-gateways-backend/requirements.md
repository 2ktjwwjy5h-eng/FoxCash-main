---
name: Gateways — visão dos adquirentes, fallback e catálogo (backend)
status: audited
references:
  - "management/vision.md#V80"
  - "management/specs/91-fallback-adquirente/requirements.md"
  - "management/specs/92-resiliencia-saldo-psp/requirements.md"
  - "management/specs/22-merchant-wallet/requirements.md"
---

# Spec 107 — Gateways: visão dos adquirentes, fallback e catálogo (backend)

## O que entrega

Backend **aditivo read-only** para a tela **Gateways** do portal (Spec 108) —
visual sobre o backend da **Spec 91** (fallback inteligente já existe). Sem
mudança no roteamento.

1. **`GET /api/v1/portal/gateways`** — visão dos adquirentes do lojista:
   `{providers: [{provider, displayName, status (ATIVO/INDISPONIVEL), order
   (principal/fallback1/fallback2), circuitState (CLOSED/OPEN/HALF_OPEN),
   volumePeriod (extrato — Σ aprovado no período), countPeriod}]}`. Fontes:
   `RoutingProperties`/`PixProviderHealthRegistry` (Spec 91), `pix_transactions.
   provider` (Spec 91) para o extrato, ordem da policy do lojista
   (`RoutingPolicyResolver`).
2. **`GET /api/v1/portal/gateways/catalog`** — catálogo disponível:
   `[{id, name, category (PIX|CARTÃO|CRIPTO), available, configured}]` —
   aditivo read-only (ativação = Spec 108/evolução; config real é do master/
   env).

## Contexto (por que agora)

Descrição de produto do usuário (2026-08-15) — detalhamento de Gateways: visão
geral dos adquirentes, fallback inteligente (Principal/Fallback 1/2) e
catálogo. Lacuna: o portal não tem nenhuma tela de gateways; o backend da Spec
91 tem a ordem/estado (mas sem exposição ao portal).

## Critérios de aceite

1. **`GET /api/v1/portal/gateways`** (ROLE_MERCHANT_PORTAL) →
   `{providers: [...]}`. Para cada provider configurado (Spec 91): `displayName`
   (mapeamento `pagarme → "Pagar.me"`, `asaas → "Asaas"`, `mock → "Simulador"`),
   `order` (1 = principal, 2/3 = fallback — da policy do lojista via
   `RoutingPolicyResolver`), `circuitState` (do `PixProviderHealthRegistry`),
   `volumePeriod` + `countPeriod` (Σ/COUNT APPROVED por `provider` no mês
   corrente — reuso agregado Spec 105). Sem providers → lista vazia (sem
   erro).
2. **`GET /api/v1/portal/gateways/catalog`** → `[{id, name, category,
   available, configured}]` — catálogo estático baseado nos adapters reais
   (Spec 91) + `configured` (se está no routing). `category` PIX/CARTÃO/CRIPTO
   (só PIX tem adapter real hoje; CARTÃO/CRIPTO = `available: false`).
3. **Sem token/segredo** em resposta (nunca secret-key do adquirente).
4. **Sem PII em logs**; rate limit `/api/v1/portal/**` (Spec 58).
5. **Contratos existentes inalterados** (Spec 91 intacta).
6. **Testes**: unitários + integração + E2E; `mvn test` verde.

## Dependências

- **Spec 91** — `RoutingPolicyResolver`, `PixProviderHealthRegistry`,
  `pix_transactions.provider`.
- **Spec 92** — (payout fallback — não muda; só referência).
- **Spec 105** — agregado por período (reuso).
- **Spec 139** (escopo + lente, `in-progress` 2026-08-16) — a **rota é por
  perfil de operação** (Spec 122 `merchant_profiles.gateway_route` + `scope`
  V76): a visão de fallback/ordem deve refletir o perfil ativo (`X-Profile-ID`);
  perfil `BOT_ONLY` **nunca** aplica override (rota por risco —
  `RoutingPolicyResolver`, Spec 139). O catálogo (`available`/`configured`)
  permanece global.

## Fora de escopo

- **Ativação/configuração de adquirentes pelo lojista** — o roteamento é config
  do master/global (Spec 91); a UI da Spec 108 mostra `available`/`configured`
  (read-only). Ativar/desativar provider por lojista = evolução (exigiria
  mudança no router).
- **UI** — Spec 108.
