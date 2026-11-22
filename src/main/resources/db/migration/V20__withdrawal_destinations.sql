-- Spec 31: destinos de saque do lojista — privados e criptografados em repouso.
-- Cada destino é um dos 4 tipos de chave Pix do Bacen (CPF/CNPJ/celular/aleatória)
-- ou dados bancários (com chave Pix associada opcional). Os campos sensíveis
-- (value/bank_code/agency/account/account_type) são criptografados AES-256/GCM
-- via CryptoConverter (prefixo 'v1:' + IV + ciphertext + tag em base64) — por
-- isso as colunas sensíveis são VARCHAR(255): o ciphertext é bem maior que o
-- valor em claro. Nunca em texto claro no banco/backup.
-- Acessível apenas pelo próprio lojista (portal); o master não tem acesso.
CREATE TABLE merchant_withdrawal_destinations (
    id           UUID PRIMARY KEY,
    merchant_id  UUID          NOT NULL REFERENCES merchants(id),
    type         VARCHAR(20)   NOT NULL,  -- PIX_CPF | PIX_CNPJ | PIX_PHONE |
                                          -- PIX_EVP | BANK_ACCOUNT
    value        VARCHAR(255),            -- chave Pix (criptografada AES-256/GCM)
    bank_code    VARCHAR(255),            -- BANK_ACCOUNT (criptografado)
    agency       VARCHAR(255),            -- BANK_ACCOUNT (criptografado)
    account      VARCHAR(255),            -- BANK_ACCOUNT (criptografado)
    account_type VARCHAR(255),            -- CHECKING | SAVINGS (criptografado)
    label        VARCHAR(60),
    status       VARCHAR(20)   NOT NULL,  -- ACTIVE | INACTIVE
    created_at   TIMESTAMP     NOT NULL,
    updated_at   TIMESTAMP     NOT NULL
);

CREATE INDEX ix_destinations_merchant ON merchant_withdrawal_destinations(merchant_id);
CREATE INDEX ix_destinations_merchant_status ON merchant_withdrawal_destinations(merchant_id, status);
