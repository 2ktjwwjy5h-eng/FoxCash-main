-- Spec 23 (2026-08-04): catálogo de Gift PIX por lojista — valores predefinidos
-- (defaults R$ 5/10/20/50) que o bot oferece ao cliente; o lojista ativa/desativa.
CREATE TABLE gift_presets (
    id          UUID PRIMARY KEY,
    merchant_id UUID         NOT NULL REFERENCES merchants(id),
    amount      NUMERIC(10,2) NOT NULL,
    label       VARCHAR(80)  NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL,
    CONSTRAINT uq_gift_presets_merchant_amount UNIQUE (merchant_id, amount)
);

CREATE INDEX ix_gift_presets_merchant_id ON gift_presets(merchant_id);
