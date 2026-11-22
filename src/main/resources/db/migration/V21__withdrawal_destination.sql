-- Spec 32: saque com destino selecionado + bloqueio lacrado com saque
-- administrativo do master.
-- destination_id: destino cadastrado pelo lojista (Spec 31) — obrigatório no
--   saque do vendedor; nulo no saque administrativo (o master informa a chave).
-- type: MERCHANT (vendedor) | ADMINISTRATIVE (recolhimento do master).
-- reason: motivo — obrigatório no saque administrativo (ações que movem dinheiro).
-- destination_key_snapshot: endereço de saque NA DATA do saque, criptografado
--   AES-256/GCM (v1:) — o histórico não muda se o destino for editado depois.
ALTER TABLE withdrawals
    ADD COLUMN destination_id UUID
        REFERENCES merchant_withdrawal_destinations(id),
    ADD COLUMN type VARCHAR(20) NOT NULL DEFAULT 'MERCHANT',
    ADD COLUMN reason VARCHAR(255),
    ADD COLUMN destination_key_snapshot VARCHAR(255);

CREATE INDEX ix_withdrawals_type ON withdrawals(type);
