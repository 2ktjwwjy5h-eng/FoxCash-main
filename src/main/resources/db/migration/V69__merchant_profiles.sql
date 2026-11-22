-- Spec 122: perfis de operação do lojista (seletor de contexto do portal,
-- fase 1 — fundação). Cada lojista tem N perfis; cada perfil carrega a rota de
-- gateway (cobrança Pix) e aponta para uma frota de bots (store_bots.profile_id).
-- gateway_route NULL/PADRAO = roteamento padrão por risco (RoutingPolicyResolver, Spec 91).

CREATE TABLE merchant_profiles (
    id            UUID PRIMARY KEY,          -- gerado pela app (padrão store_bots, V49)
    merchant_id   UUID        NOT NULL REFERENCES merchants (id) ON DELETE CASCADE,
    name          VARCHAR(64) NOT NULL,
    gateway_route VARCHAR(32),               -- NULL = PADRAO (rota por risco)
    is_default    BOOLEAN     NOT NULL DEFAULT FALSE,
    sort_order    INT         NOT NULL DEFAULT 0,
    deleted_at    TIMESTAMPTZ,               -- soft-delete (padrão store_bots)
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_merchant_profiles_merchant
    ON merchant_profiles (merchant_id) WHERE deleted_at IS NULL;

-- Backfill: 1 perfil default ("Padrão BR") por lojista existente.
INSERT INTO merchant_profiles (id, merchant_id, name, is_default, sort_order)
SELECT gen_random_uuid(), id, 'Padrão BR', TRUE, 0 FROM merchants;

-- Frota de bots por perfil: bots existentes → perfil default do lojista.
ALTER TABLE store_bots ADD COLUMN profile_id UUID REFERENCES merchant_profiles (id);
UPDATE store_bots b
   SET profile_id = mp.id
  FROM merchant_profiles mp
 WHERE mp.merchant_id = b.merchant_id AND mp.is_default;
