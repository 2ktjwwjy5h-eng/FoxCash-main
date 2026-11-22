-- Spec 49 (planos visuais + ações de entrega): parte 2 do modelo da loja.
-- (A parte 1 — stores — ficou na V36; o design original previa "V36 parte 2",
-- mas a V36 já foi consumida pelo storefront — mesmo padrão documentado:
-- "V26/V27 tomaram os números".)
--
-- plan_delivery_actions.payload: JSON criptografado (CryptoConverter — v1:)
-- plan_deliveries.platform_user_id: ID do comprador na plataforma, criptografado
-- pix_transactions.plan_id/platform/platform_user_id: vínculo da cobrança ao
-- plano + destinatário (criptografado em repouso).

CREATE TABLE plans (
    id UUID PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores(id),
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500),
    price NUMERIC(15, 2) NOT NULL,
    image_url VARCHAR(500),
    embed_color VARCHAR(9),
    featured BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE INDEX ix_plans_store_active ON plans (store_id, active);

CREATE TABLE plan_delivery_actions (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES plans(id),
    seq INT NOT NULL,
    action_type VARCHAR(30) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_plan_delivery_actions_plan_seq UNIQUE (plan_id, seq)
);

CREATE TABLE plan_deliveries (
    id UUID PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores(id),
    plan_id UUID NOT NULL REFERENCES plans(id),
    transaction_id UUID NOT NULL UNIQUE REFERENCES pix_transactions(id),
    platform VARCHAR(20) NOT NULL,
    platform_user_id VARCHAR(255) NOT NULL,
    buyer_id_hash VARCHAR(64),
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    delivered_at TIMESTAMP
);
CREATE INDEX ix_plan_deliveries_store_status ON plan_deliveries (store_id, status);

CREATE TABLE plan_delivery_actions_exec (
    id UUID PRIMARY KEY,
    delivery_id UUID NOT NULL REFERENCES plan_deliveries(id),
    seq INT NOT NULL,
    action_type VARCHAR(30) NOT NULL,
    payload_snapshot TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    note VARCHAR(255),
    executed_at TIMESTAMP,
    CONSTRAINT uq_plan_delivery_actions_exec_delivery_seq UNIQUE (delivery_id, seq)
);

ALTER TABLE pix_transactions
    ADD COLUMN plan_id UUID REFERENCES plans(id),
    ADD COLUMN platform VARCHAR(20),
    ADD COLUMN platform_user_id VARCHAR(255);
CREATE INDEX ix_pix_transactions_plan ON pix_transactions (plan_id);
