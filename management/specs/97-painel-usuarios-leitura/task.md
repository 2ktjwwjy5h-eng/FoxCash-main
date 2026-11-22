---
name: Painel de usuários — backend leitura (métricas e dados enriquecidos do lojista)
status: audited
---

# Spec 97 — Task: Painel de usuários (backend de leitura)

## F0 — Migration + domínio

- [x] **T0.1** — Migration **`V60__merchant_panel.sql`**: `ALTER TABLE merchants
      ADD COLUMN phone VARCHAR(20);` (aditiva, sem CHECK/unique). Aplicada limpa
      no boot.
- [x] **T0.2** — `MasterPanelProperties` (`@ConfigurationProperties` prefixo
      `foxcash.master-panel`) com `riskThresholds {lowPercent=2.0,
      midPercent=5.0}` + binding contra o yml real (padrão Spec 68/L16).
- [x] **T0.3** — DTOs (package `infrastructure/inbound/rest/dto`):
      `MerchantPanelItem`, `MerchantPanelDetail` (+ nested `PanelKpis`,
      `PanelAccount`, `PanelFeesAndLimits`), `MerchantPanelTransaction`,
      `MerchantStoreResponse` — records imutáveis.

## F1 — Porta + adapter (agregações SQL)

- [x] **T1.1** — Porta out `MerchantPanelRepository` (domain/port/out):
      `countPaid`, `countCreated`, `countRefunded`, `countChargebacks`,
      `sumApprovedAmount`, `lastProvider`, `merchantWithFees` (retorna
      merchant + wallet + phone + document + overrides quando existirem) +
      `buyerName`/`planName` (resolução por item — design D5).
- [x] **T1.2** — `MerchantPanelPersistenceAdapter` (infrastructure): COUNT/SUM/
      COALESCE via métodos existentes dos repositórios
      (`PixTransactionJpaRepository.countByMerchantIdAndStatus`/
      `sumAmountByMerchantIdAndStatus` — verificados existentes;
      `ChargebackJpaRepository.countByMerchantId` — existe;
      `MerchantJpaRepository.findById` para cadastro/wallet/phone/document).
- [x] **T1.3** — Máscara de documento: `DocumentMasker` extraído da Spec 31
      para CPF/CNPJ genérico (DRY — `WithdrawalDestinationMasker` delega os
      casos PIX_CPF/PIX_CNPJ).

## F2 — Service + use case

- [x] **T2.1** — `MerchantPanelQueryUseCase` (porta in) +
      `MerchantPanelQueryService` (aplicação): `searchPanel(query)` (paginado,
      filtros status/q/from/to/risk com faixas do MED), `panelDetail(merchantId)`
      (404 `merchant_not_found`), `transactions(merchantId, query)` (paginado),
      `stores(merchantId)` (lista; 404 lojista).
- [x] **T2.2** — MED ao vivo: `medPercent = (refunded + chargebacks) ÷ paid ×
      100` (0 quando paid=0), `medOccurrences = refunded + chargebacks`.
      `conversionRate = paid ÷ created × 100` (0 quando created=0).
      `gateway = lastProvider` (null → UI mostra PADRAO).
- [x] **T2.3** — Transações: `buyerName` via lead do CRM por
      `(bot_id, platform_user_id)` (fallback username; adapter do panel);
      `productName` via `plan_id` → `plans.name` (adapter do panel);
      `fee` via `PricingCalculator`; `netAmount = amount − fee`.
      Sem lead → `buyerName = null`. N+1 por item documentado (página de 20).

## F3 — Controller REST (aditivo)

- [x] **T3.1** — `MasterPanelController` (`@RequestMapping("/api/v1/master")`,
      ROLE_MASTER):
      - `GET /merchants/panel` (filtros status/q/from/to/risk + page/size via
        `QueryParamParser`, cap 100);
      - `GET /merchants/{id}/panel` (404 `merchant_not_found`);
      - `GET /merchants/{id}/transactions` (status/from/to/page/size);
      - `GET /merchants/{id}/stores` (lista).
- [x] **T3.2** — `RateLimitKey`/cardinalidade: `/api/v1/master` já coberto
      (Spec 69) — `/merchants/{id}/panel` entra no prefixo variável
      `/api/v1/master` (SEC-23) sem ajuste adicional.
- [x] **T3.3** — Nenhuma mudança nos contratos existentes
      (`/master/merchants`, `/master/transactions`, `/master/withdrawals`,
      `/admin/**`).

## F4 — Testes unitários/integração

- [x] **T4.1** — `MerchantPanelQueryServiceTest`: MED 0/0 sem pagos; MED com
      refunds+chargebacks (valor+contagem); conversão 0 sem criadas; gateway da
      última cobrança; perfil com `documentMasked`; transações com lead/sem
      lead; stores `[]` e com store; 404.
- [x] **T4.2** — `MerchantPanelPersistenceAdapterTest` (COUNTs/SUM/COALESCE +
      lastProvider limit 1 + buyerName/planName).
- [x] **T4.3** — `MasterPanelPropertiesTest` (defaults + binding).
- [x] **T4.4** — `mvn test` verde (suíte completa, sem regressão).

## F5 — E2E e fechamento

- [x] **T5.1** — E2E `master-panel.spec.js` (padrão Spec 71): login master →
      `GET /merchants/panel` (MED/conversão/pix pagos com seeds via portal:
      cobrança avulsa + cobrança via plano + webhook PAID + chargeback) →
      `GET /merchants/{id}/panel` (KPIs + documento mascarado) →
      `GET /merchants/{id}/transactions` (comprador via lead CRM / `buyerIdHash`
      sem bot; produto "VIP Semanal"; líquido) → `GET /merchants/{id}/stores` →
      401/403 + 404.
- [x] **T5.2** — `global-setup.js` (L6): truncar/limpar tabelas novas se
      necessário (nenhuma tabela nova — só coluna; sem mudança).
- [x] **T5.3** — README: documentar os endpoints novos do panel (read-only).
- [x] **T5.4** — Atualizar roadmap/status (Spec 97 → `implemented` após
      verificação).

## Verificação (`/spec-verify 97`)

- [x] Checar critérios ↔ design ↔ task contra o código real (migration V60
      livre, `QueryParamParser`, `PixTransactionJpaRepository`,
      `ChargebackJpaRepository`, `BotLeadRepository`, `PlanRepository`,
      `StoreRepository`, `PricingCalculator`, `WithdrawalDestinationMasker`,
      `MasterPermissionGuard`, `RateLimitKey`) — sem achados de escopo.

## `/spec-audit 97` (2026-08-17) — AUDITADA

**10/10 critérios OK, 0 achados abertos.** Verificação: `MasterPanelController`
com os 4 endpoints (listagem `GET /merchants/panel` paginada com MED%/
conversão/gateway; `GET /merchants/{id}/panel` com KPIs/dados da conta/taxas e
limites; `GET /merchants/{id}/transactions` com comprador (lead CRM)/produto/
líquido; `GET /merchants/{id}/stores` espelho read-only), DTOs
(`MerchantPanelItem/Detail/Transaction`), segurança ROLE_MASTER + RBAC Spec 69 +
sem PII em logs; contratos existentes inalterados (endpoints aditivos). Testes:
`MasterPanelControllerTest` 8/8 + `MasterPanelPropertiesTest` 2/2 (0 falhas) +
E2E `master-panel.spec.js` (backend) e `master-panel-ui.spec.js` (UI) presentes.
Frontmatter/roadmap → `audited`.
