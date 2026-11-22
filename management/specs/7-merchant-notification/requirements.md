---
name: Notificação ao lojista — outbox de eventos + port de entrega
status: audited
references:
  - "management/vision.md#V5"
  - "management/specs/6-invoice-scheduler/requirements.md"
---

# Spec 7 — Notificação ao lojista (fatura gerada): outbox + port

## O que entrega

Toda **fatura nova** gerada (via `POST /invoices` manual ou pelo scheduler da
Spec 6) registra um evento de notificação no **outbox** (`notification_outbox`)
na mesma transação. Um dispatcher agendado (ShedLock) entrega os eventos
pendentes através do port `MerchantNotifier`, com adapter de exemplo que apenas
loga o evento (placeholder para e-mail/webhook real).

## Critérios de aceite

1. Migration `V4__notification_outbox.sql` cria a tabela `notification_outbox`
   (FK para `merchants`, status `PENDING`/`SENT`).
2. Ao criar uma **fatura nova**, `GenerateInvoiceService` persiste um evento
   `INVOICE_GENERATED` no outbox (mesma transação); se a fatura já existe
   (idempotência), **nenhum** evento é registrado.
3. `NotificationDispatcherService.dispatchPending()` entrega os eventos
   pendentes via `MerchantNotifier` (adapter `LoggingMerchantNotifier`, mock) e
   marca como `SENT`, retornando a quantidade entregue.
4. `NotificationDispatchScheduler` roda periodicamente com `@SchedulerLock`
   (nome `foxcash-notification-dispatch`).
5. `mvn test` verde, incluindo testes dos serviços novos.

## Dependências

- Spec 6 (infra de scheduler/ShedLock e geração automática de faturas).

## Observações

- Padrão **outbox**: o evento é gravado junto com a operação de negócio
  (atomicidade); a entrega é desacoplada (worker agendado) e rastreável.
- O `LoggingMerchantNotifier` é um adapter mock — trocável por e-mail/webhook
  real sem tocar na aplicação/domínio.
- Escopo: apenas o evento `INVOICE_GENERATED`; outros tipos podem ser adicionados
  depois.
