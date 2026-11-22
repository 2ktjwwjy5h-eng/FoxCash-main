---
name: antifraud-real
status: audited
references:
  - ADR-008 (monitoramento de risco)
  - Spec 16 (antifraude/compliance)
  - docs/politicas-uso.md (§4)
---

# Spec 19 — Antifraude real

## O que entrega

Detecção e bloqueio da fraude que custa dinheiro: **chargeback** (o comprador
usa o produto e contesta no banco). Como o comprador não se identifica (nome
aleatório), o sinal é o **buyerId pseudônimo** enviado pelo bot do lojista —
um identificador opaco, **sem PII**, persistido apenas como hash SHA-256
(mesmo padrão de `document_hash`/`api_key_hash`).

1. **Triagem em tempo real** na criação de cobrança (`POST /api/v1/transactions`
   e `POST /api/v1/foxcash/pix/cob`), antes de gerar o Pix — **única regra de
   bloqueio**: comprador com **histórico de chargeback** na janela → `409
   fraud_buyer_blocked`. **Velocidade nunca bloqueia** (decisão 2026-08-04:
   compra rápida em sequência é normal; bloquear venda legítima é inaceitável).
2. **Registro de chargeback** (`POST /api/v1/master/chargebacks` e
   `POST /api/v1/merchants/{merchantId}/chargebacks`): um chargeback por
   transação, validando existência/posse; alimenta o indicador de chargeback
   da Spec 16 (antes sempre 0) e o bloqueio do comprador.
3. **Auto-suspensão de lojista**: chargeback ≥ 3% (limiar duro, `foxcash.risk.chargeback-suspend-percent`)
   suspende automaticamente (`foxcash.risk.auto-suspend-enabled=true` por
   default; o master segue podendo liberar/suspender manualmente).
4. **Score de risco 0-100** por lojista no snapshot
   (`GET /api/v1/master/risk` e `GET /api/v1/master/merchants/{id}/risk`),
   derivado das regras (reembolso +25, chargeback em alerta +40, chargeback no
   limiar duro +35, denúncias +15; cap 100).

## Critérios de aceite

1. Cobrança com buyerId que já tem chargeback na janela → **409
   `fraud_buyer_blocked`**; sem histórico → 201.
2. **Compra rápida em sequência (muitas cobranças em curto intervalo) NUNCA é
   bloqueada** — velocidade não é regra de bloqueio (sem falso positivo em
   venda legítima).
3. Registro de chargeback: 201; transação inexistente → 404
   `transaction_not_found`; transação de outro lojista → 400; duplicado → 409
   `invalid_state`.
4. Snapshot de risco reflete chargebacks registrados (taxa real), com score
   0-100.
5. Chargeback ≥ limiar duro suspende o lojista ACTIVE automaticamente; com
   `auto-suspend-enabled=false` nenhum lojista é suspenso pelo job.
6. `buyerId` nunca é devolvido em respostas nem persistido em claro
   (somente `buyer_id_hash` SHA-256).
7. `mvn test` verde (testes novos de triagem, registro e risco).

## Migração

V12: `pix_transactions.buyer_id_hash`, tabela `chargebacks` (índices por
merchant/buyer), `merchant_risk.risk_score`.
