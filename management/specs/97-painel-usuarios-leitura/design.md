---
name: Painel de usuários — backend leitura (métricas e dados enriquecidos do lojista)
status: audited
---

# Spec 97 — Design: Painel de usuários (backend de leitura)

## D1 — Conceito

Camada de **consulta agregada** para o Master Console: um novo use case de
leitura (`MerchantPanelQueryService`) que **compõe dados já existentes**
(merchants, pix_transactions, chargebacks, bot_leads, plans, stores, wallet)
em respostas ricas — sem novas tabelas de métricas (MED é derivado ao vivo) e
**sem alterar contratos existentes** (tudo aditivo sob `ROLE_MASTER`).

```text
MasterPanelController (REST, ROLE_MASTER)
   └─ MerchantPanelQueryService (aplicacao) — orquestra agregações
        ├─ MerchantPanelRepository (porta out)  → agrega COUNTs/soma em SQL
        ├─ LeadResolver (porta out)             → buyerName via bot_leads
        └─ StoreRepository (porta out existente) → espelho stores (Spec 48)
```

## D2 — Migration V60 (aditiva, uma única)

```sql
-- Spec 97: telefone opcional do lojista para o painel "Dados da Conta"
-- (a captura/edição fica na Spec 98; o cadastro Spec 39 não muda).
ALTER TABLE merchants ADD COLUMN phone VARCHAR(20);
-- null = não informado. Sem índice (acesso é por id; busca é por name/email).
```

- Número **V60** (última real é a V59 — `merchant_mfa`). Sem CHECK/unique.
- `phone` pode ser telefone celular/fixo BR livre (1–20 chars); a **validação**
  de formato fica na Spec 98 (PUT da config); aqui é só leitura.

## D3 — Endpoints (aditivos, `ROLE_MASTER`)

### D3.1 `GET /api/v1/master/merchants/panel` — listagem com métricas

Query params: `status` (enum `MerchantStatus`), `q` (LIKE em name/email, padrão
Spec 8), `from`/`to` (created_at), `page`/`size` (padrão Spec 71 — cap 100).
`risk` (baixo|medio|alto) → faixas de `medPercent` configuráveis
(`foxcash.master-panel.risk-thresholds`: low≤2%, mid≤5%, alto>5% — padrão das
políticas de uso da Spec 16; defaults documentados).

**Resposta** — `PageResponse<MerchantPanelItem>`:

```json
{
  "id": "uuid", "name": "Ramon Gontijo", "email": "r@x.com",
  "status": "ACTIVE",
  "transactionFee": 0.50, "monthlyFee": 69.95, "withdrawalFee": 2.50,
  "conversionRate": 56.62, "paidPixCount": 710,
  "medPercent": 8.73, "medOccurrences": 62,
  "gateway": "pagarme",
  "createdAt": "2026-01-01T00:00:00Z"
}
```

- `withdrawalFee`: `foxcash.billing.withdrawal-fee` (Spec 88) ou o override da
  Spec 98 quando `merchants.withdrawal_fee` não-nulo (a coluna pode existir na
  V60+ da 98; quando ausente → valor global).
- `gateway`: `pix_transactions.provider` da última cobrança; `null` sem
  cobranças (UI mostra "PADRAO").

### D3.2 `GET /api/v1/master/merchants/{id}/panel` — perfil detalhado

```json
{
  "kpis": {
    "walletBalance": 1234.56, "grossRevenue": 6567.46,
    "paidPixCount": 710, "conversionRate": 56.62, "storesCount": 1
  },
  "account": {
    "name": "Ramon Gontijo", "email": "r@x.com", "phone": "+55 11 99999-9999",
    "documentMasked": "529.***.***-25", "gateway": "pagarme",
    "createdAt": "2026-01-01T00:00:00Z"
  },
  "feesAndLimits": {
    "transactionFee": 0.50, "fixedFee": 0.40, "withdrawalFee": 2.50,
    "medPercent": 8.73, "medOccurrences": 62,
    "pixMin": null, "pixMax": null, "withdrawalMin": null, "withdrawalMax": null
  }
}
```

- `kpis.walletBalance` = `merchants.wallet_balance` (Spec 27/14).
- `kpis.grossRevenue` = Σ `pix_transactions.amount` APPROVED (histórico, sem
  janela — o filtro por período fica na UI/evolução).
- `kpis.storesCount` = COUNT `stores` do lojista (Spec 48).
- `account.documentMasked` = máscara da Spec 31 sobre `merchants.document`
  (criptografado em repouso; o masker já existe — `WithdrawalDestinationMasker`
  tem CPF/CNPJ; extrair/reescrever para documento genérico — DRY).
- `account.gateway` = mesmo cálculo do D3.1.
- `feesAndLimits.fixedFee`: hoje o modelo tem `transactionFee` fixo por plano;
  o "Taxa Fixa R$ 0,40" da descrição é exibido como `fixedFee` — fonte:
  `pricing_fee_per_transaction` (é a taxa fixa por transação). Sem duplicação
  conceitual: `transactionFee` e `fixedFee` apontam para o mesmo valor até a
  Spec 98 criar a regra de taxa configurável.
- `feesAndLimits.pixMin/pixMax/withdrawalMin/withdrawalMax`: `null` hoje
  (limites globais da Spec 26/14 — teto de valor); overrides da Spec 98 quando
  configurados.
- 404 `merchant_not_found` (padrão Spec 15 — `MasterEarningsController`).

### D3.3 `GET /api/v1/master/merchants/{id}/transactions` — com venda

Query params: `status`, `from`/`to`, `page`/`size`. **Resposta** —
`PageResponse<MerchantPanelTransaction>`:

```json
{
  "transactionId": "uuid", "amount": 4.89, "status": "APPROVED",
  "buyerName": "João da Silva", "buyerIdHash": "a1b2...",
  "productName": "VIP Semanal", "externalReference": "E123",
  "fee": 0.40, "netAmount": 4.49, "createdAt": "..."
}
```

- `buyerName`: join `bot_leads` por `(bot_id, platform_user_id)` — o
  `platform_user_id` na `pix_transactions` está **criptografado em repouso**
  (Spec 49) e em claro no lead; o join é por chave armazenada (o adapter
  descriptografa e resolve — ver D5). Sem lead → `buyerName = null`
  (a UI usa `buyerIdHash`).
- `productName`: `plans.name` via `plan_id` (Spec 49); sem plano → `null`.
- `fee`: taxa aplicada à transação via `PricingCalculator` (Spec 25) —
  `netAmount = amount − fee`.
- **Sem `pix_key`/`pix_qr_code` em claro** na listagem (payload Pix só no
  detalhe PENDING, contrato Spec 25 mantido).

### D3.4 `GET /api/v1/master/merchants/{id}/stores` — Ver Shops

`StoreResponse` (espelho do `PortalStoreController` Spec 48, read-only):
`{id, name, title, color, emoji, description, logoUrl, bannerUrl, createdAt,
updatedAt}` — **sem `social_links`** (URLs sensíveis do lojista). Lojista
inexistente → 404 `merchant_not_found`; sem store → `[]` (não 404).

## D4 — Uso de case de serviço (hexagonal)

- **Porta in**: `MerchantPanelQueryUseCase` (interface em `domain/port/in`) —
  `searchPanel(query)`, `panelDetail(merchantId)`, `transactions(merchantId,
  query)`, `stores(merchantId)`.
- **Service**: `MerchantPanelQueryService` (aplicação) — valida merchantId
  (404), orquestra as agregações, aplica máscaras, monta DTOs.
- **Porta out**: `MerchantPanelRepository` (em `domain/port/out`) com métodos
  agregados (SQL — padrão M8/Spec 30, sem carregar entidades):
  - `countPaid(merchantId)` / `countCreated(merchantId)` /
    `countRefunded(merchantId)` (COUNT por status — APPROVED/qualquer/REFUNDED).
  - `countChargebacks(merchantId)` (COUNT `chargebacks`).
  - `sumApprovedAmount(merchantId)` (Σ amount APPROVED).
  - `lastProvider(merchantId)` (provider da última cobrança).
  - `merchantWithFees(merchantId)` (dados cadastrais + wallet + phone +
    document + overrides da Spec 98 quando existirem).
- **Adapter**: `MerchantPanelPersistenceAdapter` (infrastructure) implementa
  via repositórios JPA existentes + `@Query` agregados (COUNT/SUM/COALESCE,
  padrão Spec 8/15/30).

## D5 — Resolução do comprador (lead do CRM)

- **Join por `bot_id` + `platform_user_id`**: a `pix_transactions` tem
  `bot_id` (V50) e `platform_user_id` (criptografado — Spec 49); `bot_leads`
  (V50/V57) tem `(bot_id, platform_user_id)` **UNIQUE** + `name`/`username`.
- **Implementação (adapter)**: o `BotLeadRepository` já expõe busca por
  `(botId, platformUserId)` para o funil (Spec 75). O adapter do panel faz a
  busca por cada transação (N+1 aceitável na página de 20 itens do console —
  mesmo padrão do espelho de bots da Spec 76/89: "N+1 por item, página de
  auditoria de baixa frequência"); alternativa: uma query `IN` por lote se o
  E2E mostrar lentidão (decisão documentada na implementação).
- **Segurança**: o `platform_user_id` do lead/transação é dado interno do
  lojista (Spec 49/75) — exposto **apenas** ao master (ROLE_MASTER), nunca em
  logs (`SensitiveDataLoggingFilter` mascarar `platform_user_id` — já mascarado
  desde a Spec 49).
- **Sem lead** (transação de cobrança avulsa/API sem bot): `buyerName = null`
  → UI mostra `buyerIdHash` (decisão do usuário 2026-08-15: PII via CRM apenas).

## D6 — Configuração

```yaml
foxcash:
  master-panel:
    risk-thresholds:   # faixas do filtro risk (baixo/medio/alto)
      low-percent: 2.0     # MED <= 2% → baixo
      mid-percent: 5.0     # MED <= 5% → medio; acima → alto
```

`MasterPanelProperties` + binding contra o yml real (padrão Spec 68/L16).
Defaults documentados; sem fail-fast (valores de exibição).

## D7 — Testes

**Unitários/integração:** `MerchantPanelQueryServiceTest` (MED com 0 pagos →
0/0; MED com refunds+chargebacks → valor e contagem; conversão com 0 criadas →
0; gateway da última cobrança; perfil com `documentMasked`; transações com e
sem lead; stores `[]` e com store; 404 `merchant_not_found`; 400 em parâmetro
inválido via `QueryParamParser`); `MerchantPanelPersistenceAdapterTest`
(COUNTs/SUM/COALESCE com banco de teste); binding do `MasterPanelProperties`.

**E2E (`master-panel.spec.js`):** login master → `GET /merchants/panel` com
MED/conversão/pix pagos corretos (seeds via API do portal: cobrança avulsa +
cobrança via plano + webhook PAID + chargeback) → `GET /merchants/{id}/panel`
(KPIs + documento mascarado) → `GET /merchants/{id}/transactions` (comprador
via lead do CRM no cenário de bot; `buyerIdHash` no cenário sem bot; produto
`VIP Semanal` via plano; líquido) → `GET /merchants/{id}/stores` (1 store) →
401 sem token / 403 com API key.

## D8 — Fora de escopo (reforço)

- Nenhuma escrita (config/controle/honeypot/banir/debitar = Spec 98).
- Nenhuma mudança em `/api/v1/master/merchants`, `/api/v1/master/transactions`,
  `/api/v1/master/withdrawals`, `/api/v1/admin/**` — endpoints e DTOs novos.
- Nenhum job de métricas novo (MED ao vivo; `merchant_risk` continua como está).
- Paginação das transações por período longo: mesmo padrão existente
  (`QueryParamParser`, cap 100).
