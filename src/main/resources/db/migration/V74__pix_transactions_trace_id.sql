-- Spec 127 — Trace de transação (fases 3–4 da Spec 125): traceId de negócio.
--
-- O traceId de negócio de uma cobrança É o `pix_transactions.id` (estável do
-- nascimento à morte — a idempotência da Spec 28 garante que o retry reusa a
-- MESMA transação). A coluna `trace_id` torna a convenção explícita: vínculo
-- canônico visível no banco, no TransactionTraceResponse e nos logs (MDC do
-- NotificationProcessor — correlação request do bot → webhook do PSP → worker).
--
-- Backfill: transações legadas ganham `trace_id = id` (o id é o trace de
-- negócio). NOT NULL após o backfill — toda transação nova tem traceId (o id
-- nasce no PixTransaction.create, UUID.randomUUID()).
-- Sem índice: o trace_id não é chave de lookup (o endpoint resolve por
-- id/externalReference/idempotencyKey — Spec 125).

ALTER TABLE pix_transactions ADD COLUMN trace_id UUID;

UPDATE pix_transactions SET trace_id = id WHERE trace_id IS NULL;

ALTER TABLE pix_transactions ALTER COLUMN trace_id SET NOT NULL;
