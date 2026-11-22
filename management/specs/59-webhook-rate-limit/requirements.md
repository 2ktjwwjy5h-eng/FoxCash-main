---
name: Webhook flood — rate limit por IP + throttle de log
status: audited
references:
  - "management/vision.md#V39"
  - "management/specs/10-psp-integration-foundation/requirements.md"
  - "management/specs/43-rate-limit-redis/requirements.md"
  - "management/specs/36-payout-psp/requirements.md"
---

# Spec 59 — Webhook flood: rate limit + log throttle

## O que entrega

Corrige o achado **CRÍTICO** da análise de resiliência (2026-08-07): os
endpoints públicos `/webhooks/pix` e `/webhooks/payouts` não têm rate limit — o
único gate é a assinatura HMAC. Com o segredo válido (ou vazado), um atacante
pode floodar webhooks legítimos; sem o segredo, gera flood de 401 + WARN por
request (spam de log). Entregáveis:

1. **Rate limit por IP em `/webhooks/pix` e `/webhooks/payouts`** — janela
   fixa com teto **generoso** (não quebrar reentrega legítima do PSP; a
   idempotência do backend já cobre replay).
2. **Throttle de log de assinatura inválida** — o WARN de 401 em flood passa a
   ser amostrado/throttled (ex.: 1 log por intervalo), preservando diagnóstico
   sem explodir o volume de logs.

## Critérios de aceite

1. Flood de webhooks do MESMO IP acima do teto → **429 `too_many_requests`**.
2. Reentrega legítima do PSP (retry/backoff em minutos) **não** dispara 429 —
   o teto é ≥ a reentrega máxima esperada.
3. Webhook com assinatura inválida continua → **401** (inalterado), mas o log
   WARN é throttled: N requests inválidos geram ≤ M linhas de log (ex.: 1 a
   cada 10s), não 1 por request.
4. Webhook válido abaixo do teto → **204** (inalterado); replay idempotente
   continua 204 sem efeitos (Spec 36/28).
5. `mvn test` sem regressão; E2E psp-flow (webhooks legítimos) sem regressão.
6. Com `store=redis` (Spec 43), o contador por IP é agregado entre instâncias.

## Filosofia de calibração (baseline ≠ definitivo)

O teto de **120/min/IP** é **baseline**, não meta. Objetivo: **degradação
controlada** — sob flood, o excesso é cortado com 429 antes de saturar
CPU/DB/threads; a reentrega legítima do PSP (minutos) nunca é bloqueada; a
idempotência do backend (Spec 28/36) segue garantindo que **nenhum evento é
processado duas vezes** e **nenhum pagamento legítimo é perdido** (o 429
atrasa, o PSP reentrega, o processamento converge).

Calibrar no Railway com benchmark: medir o throughput sustentável de webhooks
(válidos + inválidos) e ajustar o teto para operar **antes** da saturação.
Resultado registrado no roadmap.

## Critérios de integridade sob sobrecarga

7. **Sem perda de pagamento legítimo:** webhook válido que leva 429 (flood do
   mesmo IP) é reentregue pelo PSP e processado com **204** — o estado final da
   transação é correto (APPROVED/COMPLETED) mesmo sob rejeição temporária.
8. **Sem duplicidade:** mesmo com flood + reentrega, `SALE_CREDIT`/
   `WITHDRAWAL_DEBIT`/`WITHDRAWAL_REVERSAL` aparecem **uma única vez** no ledger
   (locks + idempotência das Specs 26/27/36); reconciliação (Spec 30) passa.
9. **Outbox íntegro:** nenhum evento da fila de SAÍDA (Spec 35/53) é perdido ou
   duplicado por causa do rate limit dos webhooks de ENTRADA.
10. **Sem OOM/restart:** heap monitorado; zero `OutOfMemoryError` no pico de
    flood.
