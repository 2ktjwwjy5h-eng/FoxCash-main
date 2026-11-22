-- Spec 37: Auditoria de acoes do master — trilha imutavel (append-only) de toda
-- acao administrativa, com actor, before/after e motivo.

-- master_audit_log: entrada imutavel por acao administrativa do master.
--   action: WITHDRAWALS_BLOCK | WITHDRAWALS_RELEASE | ADMINISTRATIVE_WITHDRAWAL |
--           RECEIVABLE_WRITE_OFF | INVOICE_COLLECT | RISK_ADJUSTMENT.
--   merchant_id: alvo (quando houver).
--   reference_type/reference_id: WITHDRAWAL | RECEIVABLE | INVOICE | MERCHANT.
--   before_json/after_json: estado anterior/posterior (JSON, dados sensiveis
--                           mascarados - nunca em claro).
--   reason: motivo (obrigatorio em acoes que alteram dinheiro).
--   ip: endereco de origem da requisicao.
CREATE TABLE master_audit_log (
    id             UUID PRIMARY KEY,
    actor_id       UUID NOT NULL REFERENCES masters(id),
    action         VARCHAR(50) NOT NULL,
    merchant_id    UUID REFERENCES merchants(id),
    reference_type VARCHAR(20),
    reference_id   UUID,
    before_json    TEXT,
    after_json     TEXT,
    reason         VARCHAR(255),
    ip             VARCHAR(45),
    created_at     TIMESTAMP NOT NULL
);

CREATE INDEX ix_audit_merchant_created ON master_audit_log(merchant_id, created_at);
CREATE INDEX ix_audit_actor_created   ON master_audit_log(actor_id, created_at);
CREATE INDEX ix_audit_action_created  ON master_audit_log(action, created_at);
