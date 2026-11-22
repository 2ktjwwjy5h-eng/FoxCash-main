-- Spec 39: cadastro com nome, e-mail e senha — sem CPF/CNPJ.
-- E-mail vira o identificador único de login; o documento (CPF/CNPJ) deixa de
-- ser obrigatório (lojistas legados preservados — coluna anulável); os campos
-- trading_name/legal_name são substituídos por um único `name` (backfill).

ALTER TABLE merchants ADD COLUMN email VARCHAR(255);

ALTER TABLE merchants ADD COLUMN name VARCHAR(120) NOT NULL DEFAULT '';
UPDATE merchants SET name = COALESCE(legal_name, trading_name, '');

-- Documento opcional: novos cadastros não têm CPF/CNPJ.
ALTER TABLE merchants ALTER COLUMN document DROP NOT NULL;
ALTER TABLE merchants ALTER COLUMN document_hash DROP NOT NULL;

-- Unicidade do documento apenas quando presente (parcial).
DROP INDEX uq_merchants_document_hash;
CREATE UNIQUE INDEX uq_merchants_document_hash
    ON merchants(document_hash) WHERE document_hash IS NOT NULL;

-- E-mail único, normalizado em minúsculas (busca exata por lower(email)).
CREATE UNIQUE INDEX uq_merchants_email ON merchants(lower(email))
    WHERE email IS NOT NULL;

-- Nome único substitui trading_name/legal_name (backfill acima).
ALTER TABLE merchants DROP COLUMN trading_name;
ALTER TABLE merchants DROP COLUMN legal_name;
