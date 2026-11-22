---
name: Idempotência na criação de cobrança — idempotencyKey por lojista
status: audited
references:
  - "management/vision.md#V18"
  - "management/specs/11-pagarme-adapter/requirements.md"
  - "management/specs/26-qa-hardening-financeiro/requirements.md"
  - "management/specs/19-antifraud-real/requirements.md"
  - "management/specs/23-pix-charge-generator/requirements.md"
---

# Spec 28 — Idempotência na criação de cobrança

## O que entrega

Hoje, ao criar uma cobrança (`POST /api/v1/transactions`,
`POST /api/v1/foxcash/pix/cob`, `POST /api/v1/portal/charges` e gift), o fluxo é:
criar transação em memória → chamar o PSP → persistir. Se o PSP **processou a
cobrança mas a resposta se perdeu** (timeout de 5s/10s da Spec 26), o cliente
recebe erro e **reenvia → cria-se uma NOVA transação + NOVA cobrança no PSP** —
o comprador pode pagar duas vezes. Diagnóstico Fase 5, achado **C2**.

Esta spec torna a criação de cobrança **idempotente por `idempotencyKey`**
(chave do cliente, única por lojista): reenviar o mesmo request com a mesma
chave **retorna a mesma cobrança** (mesmo `transactionId`), nunca duplicando
no PSP.

1. **`idempotencyKey` obrigatória** nas rotas de cobrança (`400
   invalid_argument` se ausente/vazia) — mudança de contrato intencional,
   prática padrão de gateways (Stripe/Pagar.me).
2. **Migration V29**: `pix_transactions.idempotency_key VARCHAR(64)` + índice
   único parcial `(merchant_id, idempotency_key)` — a unicidade é **por
   lojista** (lojistas diferentes podem reusar a mesma chave).
3. **Persistência antecipada**: a transação `PENDING` é persistida **antes** da
   chamada ao PSP (transação própria `REQUIRES_NEW`) — um timeout **não**
   apaga o registro; a constraint única garante que o retry nunca duplica.
4. **Retry idempotente**: com a mesma chave, o serviço reusa a transação
   existente: se a cobrança já tem QR → retorna a existente; se ficou `PENDING`
   sem QR (timeout anterior) → **re-tenta a cobrança no PSP com o mesmo
   `transactionId`** (o `code` enviado ao Pagar.me é o próprio transactionId —
   idempotente no PSP real) e retorna a mesma.
5. **Erro real do PSP**: a transação é marcada `FAILED` (nova transição
   `PixTransaction.fail()`); retry da mesma chave → `409 idempotency_conflict`
   (a chave já produziu resultado; o cliente deve trocar de chave).
6. **Timeout do PSP**: resposta `502 psp_timeout`; a transação `PENDING` sem QR
   persiste e é recuperável pelo retry com a mesma chave (ou pela reconciliação
   da Spec 30).

## Critérios de aceite

1. `idempotencyKey` obrigatória em `POST /api/v1/transactions`,
   `POST /api/v1/foxcash/pix/cob`, `POST /api/v1/portal/charges` e
   `POST /api/v1/portal/charges/gift` → ausente/vazia responde `400
   invalid_argument`.
2. Migration V29: coluna `idempotency_key` + índice único parcial
   `(merchant_id, idempotency_key)` (verificado no banco).
3. Replay da mesma chave (mesmo lojista) após cobrança criada → **mesmo
   `transactionId`** e sem nova chamada ao PSP (verificado no mock).
4. Replay após `APPROVED` (webhook) → retorna a transação aprovada (sem
   re-criar cobrança).
5. Timeout simulado no PSP → transação `PENDING` persistida (sem QR); retry
   com a mesma chave **conclui a cobrança** e retorna o mesmo `transactionId`
   (sem duplicidade).
6. Erro real do PSP (ex.: 422) → transação `FAILED`; retry da mesma chave →
   `409 idempotency_conflict`.
7. Mesma chave em lojistas diferentes → **permitida** (unicidade por lojista).
8. `mvn test` verde (204 + novos) e E2E sem regressão (**10/10** + cenário de
   replay/timeout).

## Dependências

- Spec 11 (adapter Pagar.me — `code` = transactionId; base da idempotência no
  PSP real).
- Spec 26 (timeouts 5s/10s — origem do cenário de timeout; teto de valor).
- Spec 19 (triagem antifraude — permanece antes da criação).
- Spec 23 (portal charges/gift — rotas afetadas).

## Observações

- **Mudança de contrato:** `idempotencyKey` passa a ser obrigatória. Justifica:
  sem ela o gateway não consegue garantir "cobrança criada no máximo uma vez"
  sob timeout — requisito da Fase 5 (nenhum erro financeiro). Documentado no
  README (seção de uso).
- **Persistência antecipada `REQUIRES_NEW`:** implementada via
  `TransactionTemplate` (evita problema de self-invocation de proxy
  `@Transactional`); a transação `PENDING` com a chave é gravada em transação
  própria e depois atualizada pelo fluxo principal.
- **`code` do Pagar.me = `transactionId`:** re-enviar `createCharge` com o
  mesmo `code` retorna a cobrança existente no PSP (idempotência do provedor);
  o mock deve ser ajustado para reproduzir esse comportamento no teste.
- **Reconciliação (Spec 30)** usa as transações `PENDING` sem `externalReference`
  como candidatas a cobrança órfã (timeout não recuperado).
