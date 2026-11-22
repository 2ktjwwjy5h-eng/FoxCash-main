---
name: Webhook de saída para o bot — eventos de venda entregues via POST assinado com retry
status: audited
references:
  - "management/vision.md#V20"
  - "management/specs/7-merchant-notification/requirements.md"
  - "management/specs/29-consistent-refund/requirements.md"
  - "management/specs/32-withdrawal-destination-flow/requirements.md"
  - "management/specs/6-invoice-scheduler/requirements.md"
---

# Spec 35 — Webhook de saída para o bot do lojista

## O que entrega

Hoje o bot do lojista **consulta** o status da cobrança (o `MerchantNotifier`
da Spec 7 é um mock que só loga). O bot precisa ser **avisado** — venda paga,
estornada, cobrança expirada — para concluir a entrega do produto ao comprador.
Diagnóstico Fase 6 (**B2**).

Esta spec entrega **webhooks de saída** reais para o lojista:

1. **Configuração de webhook no portal** (tabela `merchant_webhooks`, migration
   V24): URL de destino + segredo (criptografado AES-256/GCM) + eventos
   assinados. Privado por lojista (padrão Spec 31): só o dono gerencia os seus,
   listagem sem expor o segredo, 404 cross-loja.
2. **Eventos publicados no outbox** (Spec 7), nos pontos de origem, com payload
   JSON documentado e `eventId` (idempotência para o receptor):
   - `CHARGE_CREATED` (cobrança criada — QR);
   - `SALE_PAID` (transação `APPROVED`);
   - `SALE_REFUNDED` (transação `REFUNDED`);
   - `CHARGEBACK_REGISTERED`;
   - `WITHDRAWAL_COMPLETED`;
   - `INVOICE_GENERATED` e `INVOICE_COLLECTED`.
3. **Entrega HTTP assinada:** novo adapter `HttpMerchantNotifier` faz
   `POST` para a URL configurada com header `X-FoxCash-Signature` (HMAC-SHA256
   do raw body com o segredo do lojista) e `X-FoxCash-Event-Id`/`X-FoxCash-Event`.
4. **Retry com backoff:** o outbox ganha colunas de entrega (`attempts`,
   `last_error`, `next_attempt_at`); o dispatcher re-tenta com backoff
   exponencial (máx configurável, default 5 tentativas) e marca `SENT`/`FAILED`.
5. **Teste de entrega:** `POST /api/v1/portal/webhooks/test` envia um evento
   ping assinado para validar a URL (200/4xx reportado).

**Contrato do payload (todos os eventos):** `{eventId, type, merchantId,
createdAt, data:{...}}` — `data` varia por evento (ex.: `SALE_PAID` →
`{transactionId, amount, paidAt}`).

## Critérios de aceite

1. Migration V24: `merchant_webhooks` (merchant_id, url, secret criptografado,
   events, status ACTIVE/INACTIVE, timestamps) + `notification_outbox` ganha
   `attempts`/`last_error`/`next_attempt_at` (verificado no banco: secret com
   prefixo `v1:`).
2. CRUD no portal (`GET`/`POST`/`PATCH`/`POST /{id}/activate|deactivate`):
   privado por lojista (404 cross-loja), segredo nunca devolvido em claro,
   401/403 de auth, evento inválido → 400.
3. Cada ponto de origem publica o evento no outbox **na mesma transação**
   (verificado: cobrança criada → `CHARGE_CREATED`; webhook PAID →
   `SALE_PAID`; REFUNDED → `SALE_REFUNDED`; chargeback → `CHARGEBACK_REGISTERED`;
   saque → `WITHDRAWAL_COMPLETED`; fatura paga → `INVOICE_COLLECTED`).
4. Entrega: `POST` assinado HMAC-SHA256 com `X-FoxCash-Signature` verificável
   (teste reproduz a assinatura); resposta 2xx → `SENT`; erro/4xx/5xx → retry
   com backoff até o máx de tentativas → `FAILED` (com `last_error`).
5. Idempotência no payload: `eventId` único por evento (re-entrega não
   duplica); o receptor pode deduplicar.
6. `POST /api/v1/portal/webhooks/test` → 200 com `{delivered: true/false,
   statusCode?, error?}`.
7. `mvn test` verde (204 + novos) e E2E sem regressão (**10/10** + cenário 15:
   configurar webhook, receber `SALE_PAID` assinado, retry em falha).

## Dependências

- Spec 7 (outbox e dispatcher — base a evoluir).
- Spec 29 (estorno — publica `SALE_REFUNDED`/`CHARGEBACK_REGISTERED`).
- Spec 32 (saque — publica `WITHDRAWAL_COMPLETED`).
- Spec 33 (fatura paga — publica `INVOICE_COLLECTED`).
- Spec 20 (auth do portal para a configuração).

## Observações

- **O mock (Spec 7) permanece como fallback** quando o lojista não tem webhook
  configurado (log); com webhook ativo, a entrega é HTTP assinada.
- **Segredo do webhook** é criptografado em repouso (padrão do projeto) e
  usado apenas para assinar/verificar — nunca exposto em resposta.
- **Eventos assinados:** o lojista deve verificar a assinatura (contrato
  documentado no README); a plataforma não re-entrega após `FAILED` (fica para
  o lojista consultar — a reconciliação da Spec 30 continua sendo o backstop).
