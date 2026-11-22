-- Spec 50 (botões, menu e fluxo configurável da loja): menu inicial da loja.
-- O vendedor monta o fluxo no painel; o bot apenas renderiza (Spec 52).
-- action: BUY/PREVIEW/FAQ/SOCIAL/LINK/CLOSE.
-- payload: JSON tipado (mediaUrls[]/text/url+label) — SEMPRE criptografado
--          (CryptoConverter — v1:; pode conter link privado/canal VIP).
-- position: ordem do menu 0..N (unique por loja — reordenação recalcula).

CREATE TABLE store_buttons (
    id UUID PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores(id),
    label VARCHAR(80) NOT NULL,
    action VARCHAR(30) NOT NULL,
    payload TEXT,
    position INT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_store_buttons_store_position UNIQUE (store_id, position)
);
CREATE INDEX ix_store_buttons_store ON store_buttons (store_id);
