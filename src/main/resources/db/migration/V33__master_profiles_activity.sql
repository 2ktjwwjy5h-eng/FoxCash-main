-- Spec 41: (1) gestao de perfis master autorizada — flag can_manage_masters no
-- perfil (o primeiro master, seed ou mais antigo, vira admin); (2) relatorio de
-- atividades — trilha unificada platform_activity_log (append-only) de eventos
-- MASTER/MERCHANT/SYSTEM.

-- Parte 1: perfil master — flag de administrador (cria/gerencia perfis).
ALTER TABLE masters ADD COLUMN can_manage_masters BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfill: o master mais antigo vira admin (o seed cria o 1º com TRUE).
UPDATE masters SET can_manage_masters = TRUE
    WHERE id = (SELECT id FROM masters ORDER BY created_at ASC LIMIT 1);

-- Parte 2: trilha unificada de atividades da plataforma (relatorio do admin).
--   actor_type: MASTER | MERCHANT | SYSTEM (jobs).
--   actor_id:   master.id() / merchant.id() (null p/ SYSTEM).
--   action:     MASTER_LOGIN, MASTER_LOGOUT, MASTER_CREATED, MASTER_ACTIVATED,
--               MASTER_DEACTIVATED, MERCHANT_REGISTERED, MERCHANT_LOGIN,
--               MERCHANT_LOGOUT, PASSWORD_RESET, CHARGE_CREATED, SALE_PAID,
--               SALE_REFUNDED, CHARGEBACK_REGISTERED, WITHDRAWAL_REQUESTED,
--               WITHDRAWAL_COMPLETED, WITHDRAWAL_FAILED, INVOICE_COLLECTED.
--   entity_type/entity_id: origem do evento (MERCHANT|TRANSACTION|WITHDRAWAL|
--               INVOICE|SESSION|MASTER...).
--   merchant_id: lojista afetado (null p/ acoes globais).
--   details: JSON serializado (mesmo padrao do master_audit_log; dados
--            sensiveis mascarados — nunca em claro).
--   ip: endereco de origem (null em jobs/SYSTEM).
CREATE TABLE platform_activity_log (
    id          UUID PRIMARY KEY,
    actor_type  VARCHAR(16) NOT NULL,
    actor_id    UUID,
    action      VARCHAR(64) NOT NULL,
    entity_type VARCHAR(32),
    entity_id   UUID,
    merchant_id UUID,
    details     TEXT,
    ip          VARCHAR(45),
    created_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_platform_activity_created ON platform_activity_log(created_at);
CREATE INDEX ix_platform_activity_merchant ON platform_activity_log(merchant_id);
CREATE INDEX ix_platform_activity_action ON platform_activity_log(action);
