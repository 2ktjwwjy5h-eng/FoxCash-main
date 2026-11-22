-- Causa raiz do travamento do dispatcher (Fase 2A, 2026-08-09, docs §11):
-- findPendingDue rodava Seq Scan na tabela de ~2M linhas a cada poll (10s) — o
-- predicado (next_attempt_at IS NULL OR next_attempt_at <= now) impedia o uso
-- do indice (status, next_attempt_at). Sob carga o scan degradava e a run
-- esticava (o lock PT40M escondia o travamento por 40min).
-- Indice parcial para o caso dominante (eventos nunca tentados, ~99% do
-- backlog) em ordem FIFO — a query passa a andar pelo indice e parar no LIMIT.
CREATE INDEX ix_notification_outbox_pending_created
    ON notification_outbox (created_at)
    WHERE status = 'PENDING' AND next_attempt_at IS NULL;
