-- Decisao de produto: sem KYC — a compra exige apenas nome + telefone do
-- comprador; o CPF (payer_document) permanece opcional. Colunas criptografadas
-- em repouso (CryptoConverter) como o payer_document.
ALTER TABLE pix_transactions ADD COLUMN payer_name VARCHAR(255);
ALTER TABLE pix_transactions ADD COLUMN payer_phone VARCHAR(255);
