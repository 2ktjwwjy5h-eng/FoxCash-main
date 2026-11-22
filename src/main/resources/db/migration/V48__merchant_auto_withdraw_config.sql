-- Spec 70: saque automático por limiar de saldo (auto-withdraw).
--
-- 1:1 por lojista (merchant_id UNIQUE — mesmo padrão de stores, Spec 48).
-- Linha ausente = desabilitado (a API devolve defaults calculados, nunca 404).
-- destination_id NOT NULL com ON DELETE RESTRICT: a config nunca referencia um
-- destino inexistente — remover o destino exige desativar o automático antes
-- (RESTRICT deixa o DELETE falhar com FK; comportamento intencional, D11.4).
CREATE TABLE merchant_auto_withdraw_config (
    id                UUID PRIMARY KEY,
    merchant_id       UUID         NOT NULL UNIQUE REFERENCES merchants (id) ON DELETE CASCADE,
    enabled           BOOLEAN      NOT NULL DEFAULT FALSE,
    threshold_amount  NUMERIC(15,2) NOT NULL,
    destination_id    UUID         NOT NULL REFERENCES merchant_withdrawal_destinations (id) ON DELETE RESTRICT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- O job consulta as configs ativas a cada execução.
CREATE INDEX ix_merchant_auto_withdraw_enabled ON merchant_auto_withdraw_config (enabled);
