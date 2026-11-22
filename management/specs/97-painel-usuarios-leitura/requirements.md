---
name: Painel de usuários — backend leitura (métricas e dados enriquecidos do lojista)
status: audited
references:
  - "management/vision.md#V73"
  - "management/specs/71-master-console/requirements.md"
  - "management/specs/15-master-dashboard/requirements.md"
  - "management/specs/16-antifraud-compliance/requirements.md"
  - "management/specs/19-antifraud-real/requirements.md"
  - "management/specs/27-wallet-ledger/requirements.md"
  - "management/specs/75-leads-metricas-bots/requirements.md"
  - "management/specs/49-planos-entregas/requirements.md"
  - "management/specs/48-stores-storefront/requirements.md"
  - "management/specs/88-withdrawal-fee/requirements.md"
  - "management/specs/91-fallback-adquirente/requirements.md"
---

# Spec 97 — Painel de usuários: backend de leitura (métricas e dados enriquecidos do lojista)

## O que entrega

Backend **somente-leitura** que transforma a listagem básica de lojistas do
Master Console (Spec 71 — `MerchantResponse` com `id/name/email/status/
transactionFee/monthlyFee/createdAt`) em um **painel rico de operação e risco**,
sem alterar nenhum contrato HTTP existente (tudo aditivo sob `ROLE_MASTER`).

1. **Tabela de lojistas com métricas de risco** — para cada lojista:
   - **MED%** — termômetro de risco (ex.: 8.73% / 62 ocorrências): índice
     derivado ao vivo = `(REFUNDED + chargebacks) ÷ pix pagos × 100`, com a
     contagem de ocorrências (ex.: 62).
   - **Taxa de conversão** — `pix pagos ÷ pix criados × 100`.
   - **Nº exato de cobranças/Pix pagos** (`paidPixCount`).
   - **Gateway/rota efetiva** — `PADRAO` (routing global da Spec 91) ou o
     provedor da última cobrança (`pix_transactions.provider`).
   - Taxas aplicadas (Taxa Pix = `pricing_fee_per_transaction`, Taxa Fixa,
     Taxa de Saque = `withdrawal-fee` global da Spec 88 — override da Spec 98
     quando presente).
2. **Perfil detalhado do lojista** (`GET /api/v1/master/merchants/{id}/panel`):
   - **KPIs de topo**: saldo atual na carteira principal, faturamento histórico
     com nº de Pix pagos, taxa de conversão global, nº de shops vinculados.
   - **Dados da conta**: e-mail, telefone (coluna nova opcional), CPF/CNPJ
     **mascarado**, titular (`name`), gateway/rota, data de cadastro.
   - **Painel de taxas e limites**: Taxa Pix, Taxa Fixa, Taxa de Saque, MED
     acumulado (índice + ocorrências) e limites operacionais de Pix e Saque
     (globais hoje; overrides da Spec 98 quando configurados).
3. **Transações com dados de venda** (`GET /api/v1/master/merchants/{id}/transactions`):
   valor, status (PENDENTE/PAGO), **nome do comprador** (via lead do CRM —
   `bot_leads.name/username` por `bot_id`+`platform_user_id`; sem lead →
   `buyerIdHash` opaco), **produto/serviço** (nome do plano via `plan_id`),
   TXID (`external_reference`), e **líquido** (bruto − taxa da transação).
4. **Saques** — reuso do `GET /api/v1/master/withdrawals?merchantId=` existente
   (já tem `fee` da Spec 88 e destino mascarado); a Spec 99 consome direto.
5. **Espelho read-only de stores** (`GET /api/v1/master/merchants/{id}/stores`
   — "Ver Shops"): lista das stores do lojista (hoje só existe no portal, Spec
   48) com identidade e datas.

## Contexto (por que agora)

Descrição de produto do usuário (2026-08-15) do **Painel Geral de Usuários** do
sistema de referência: tabela rica com coluna **MED%** (termômetro de risco,
ex.: "Ramon Gontijo — 8.73% MED / 62 ocorrências"), perfil com raio-x
financeiro, transações com comprador/produto e histórico de saques. Análise da
lacuna contra o código real: o `GET /api/v1/master/merchants` (Spec 71) devolve
só dados cadastrais básicos; o `merchant_risk` (Spec 16/19) tem `risk_score` e
taxas de refund/chargeback no snapshot **diário**, mas não expõe MED com
contagem; transações do master não têm nome do comprador nem produto; não há
espelho de stores no master. Decisão do usuário (2026-08-15): **nome do
comprador via lead do CRM** (`bot_leads`, Specs 75/83) — sem PII nova no
backend (transação sem lead → `buyerIdHash` opaco).

## Critérios de aceite

1. **API Master — listagem com métricas:** `GET /api/v1/master/merchants/panel`
   (ROLE_MASTER, aditivo — o `GET /api/v1/master/merchants` da Spec 71 fica
   intacto), paginada (default/cap como os demais lists do master — cap 100),
   filtros `status`, `q` (nome/e-mail), `from`/`to`, `risk` (baixo/médio/alto
   via faixas do `medPercent`). Cada item:
   `{id, name, email, status, transactionFee, monthlyFee, withdrawalFee,
   conversionRate, paidPixCount, medPercent, medOccurrences, gateway,
   createdAt}`.
2. **MED% derivado ao vivo:** `medPercent = (refundedCount + chargebackCount) ÷
   paidPixCount × 100` (0 quando `paidPixCount = 0`); `medOccurrences =
   refundedCount + chargebackCount`. Fontes: `pix_transactions` com status
   `REFUNDED` (Spec 29) + tabela `chargebacks` (Spec 19) — contagens agregadas
   por lojista via SQL (padrão M8/Spec 30). Não usa o snapshot diário
   (`merchant_risk`) como fonte do MED (é termômetro em tempo real); o snapshot
   continua no endpoint `/risk` existente.
3. **Conversão e Pix pagos:** `conversionRate = paidPixCount ÷ createdCount ×
   100` (0 quando sem criadas); `paidPixCount` = `pix_transactions` com status
   `APPROVED` (Spec 25 — REFUNDED/chargeback não contam como venda).
4. **Gateway/rota efetiva:** `gateway` = `pix_transactions.provider` da última
   cobrança (ORDER BY created_at DESC LIMIT 1 — Spec 91); sem cobranças → `null`
   (a UI mostra "PADRAO"). O override de rota é **por perfil de operação**
   (`merchant_profiles`, Spec 122 — o master console não gerencia rota); o panel
   do master reflete a rota efetiva (`provider`) sem mudança.
5. **API Master — perfil:** `GET /api/v1/master/merchants/{id}/panel` →
   `{kpis: {walletBalance, grossRevenue, paidPixCount, conversionRate,
   storesCount}, account: {name, email, phone, documentMasked, gateway,
   createdAt}, feesAndLimits: {transactionFee, fixedFee, withdrawalFee,
   medPercent, medOccurrences, pixMin, pixMax, withdrawalMin,
   withdrawalMax}}`. Lojista inexistente → 404 `merchant_not_found` (padrão
   Spec 15). `documentMasked` usa o mascarador da Spec 31 (`529.***.***-25`);
   telefone/limites `null` até serem preenchidos (Spec 98).
6. **Transações com comprador/produto/líquido:** `GET
   /api/v1/master/merchants/{id}/transactions` (aditivo — o
   `GET /api/v1/master/transactions` existente fica intacto) → `PageResponse`
   de `{transactionId, amount, status, buyerName, buyerIdHash, productName,
   externalReference, fee, netAmount, createdAt}`. `buyerName` = lead do CRM
   (`bot_leads.name`; fallback `username`) via `bot_id`+`platform_user_id`
   (Spec 75/83); sem lead → `buyerName = null` e a UI mostra `buyerIdHash`
   opaco. `productName` = `plans.name` via `plan_id` (Spec 49). `netAmount =
   amount − fee` (taxa do plano — `PricingCalculator`, mesma fonte das faturas
   Spec 25).
7. **Stores (Ver Shops):** `GET /api/v1/master/merchants/{id}/stores` → lista
   das stores do lojista (1:1 hoje — Spec 48): `{id, name, title, color, emoji,
   description, createdAt, updatedAt}` (sem `social_links` sensíveis; URL de
   logo/banner opcional). Lojista inexistente → 404.
8. **Segurança e rate limit:** rotas novas sob `/api/v1/master/**` — ROLE_MASTER
   (RBAC Spec 69), cardinalidade do `RateLimitKey` já cobre `/api/v1/master`
   (Spec 69/SEC-23); sem PII em logs (e-mail/telefone mascarados —
   `SensitiveDataLoggingFilter`); sem token/segredo de bot em resposta.
9. **Contratos existentes inalterados:** `/api/v1/master/merchants`,
   `/api/v1/master/transactions`, `/api/v1/master/withdrawals`,
   `/api/v1/master/risk`, `/api/v1/admin/**` — nenhuma mudança (endpoints
   novos, DTOs novos).
10. **Testes:** unitários + integração (MED/conversão com 0, cálculo com
    refunds+chargebacks, gateway da última cobrança, perfil com máscara,
    transações com/sem lead, stores 404, 400 em parâmetro inválido, 401/403) e
    E2E Playwright (login master → listagem com MED/conversão → detalhe → aba
    transações com comprador/produto/líquido → Ver Shops); `mvn test` verde +
    E2E completo sem regressão.

## Dependências

- **Spec 71** (console) — padrão de endpoints master read-only + cap de
  paginação (`QueryParamParser`).
- **Spec 15** (dashboard master) — `MasterDashboardPort`/earnings (KPIs).
- **Spec 16/19** (risco) — tabela `chargebacks`, statuses `REFUNDED`.
- **Spec 27** (ledger) — `wallet_balance` (KPIs de topo).
- **Spec 75/83** (funil/CRM) — `bot_leads.name/username` por
  `bot_id`+`platform_user_id` (nome do comprador).
- **Spec 49** (planos) — `plans.name` via `plan_id` (produto/serviço).
- **Spec 48** (stores) — espelho read-only no master.
- **Spec 88** (taxa de saque) — `withdrawal-fee` global + `fee` no saque.
- **Spec 91** (routing) — `pix_transactions.provider` (gateway efetivo).
- **Spec 73** (UX) — consumida pela Spec 99 (não por esta).

## Fora de escopo

- **Escrita** — configuração por lojista, honeypot, debitar saldo, banir,
  bloquear API: **Spec 98**.
- **UI** — tabela, perfil, abas Transações/Saques, Ver Shops no console:
  **Spec 99**.
- **Telefone do lojista no cadastro** — a coluna `merchants.phone` nasce aqui
  (V60, opcional, para o painel exibir); a **captura/edição** fica na Spec 98
  (campo na config) — o cadastro (Spec 39) não muda.
- **Rejeitar conta** — fora de escopo (não há estado PENDING; aprovação manual
  é spec futura).
- **Cálculo de MED em job** — é derivado ao vivo no panel; o snapshot diário
  `merchant_risk` (Spec 16) permanece como está.
