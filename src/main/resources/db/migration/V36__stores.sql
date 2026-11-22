-- Spec 48 (loja white-label multi-vendedora): loja do vendedor.
-- 1 loja por merchant (UNIQUE) — modelagem preparada para 1:N no futuro
-- (remover o UNIQUE sem redesenho; o storefront passa a resolver por id).
-- social_links: JSON array de {label, url} (max. 10).
CREATE TABLE stores (
    id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL UNIQUE REFERENCES merchants(id),
    name VARCHAR(120) NOT NULL,
    logo_url VARCHAR(500),
    banner_url VARCHAR(500),
    description VARCHAR(500),
    social_links TEXT,
    color VARCHAR(9),
    emoji VARCHAR(10),
    title VARCHAR(120),
    footer VARCHAR(500),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
