-- Decisao de produto (2026-08-04): gateway somente para o vendedor.
-- O contato com o cliente e do bot do lojista; o gateway nao captura nem
-- armazena dados do comprador (LGPD — minimizacao). As colunas adicionadas
-- nas migrations V2 (payer_document) e V10 (payer_name, payer_phone) sao
-- removidas; o fluxo de cobranca (POST /api/v1/nyx/pix/cob) nao recebe mais
-- esses campos.
ALTER TABLE pix_transactions DROP COLUMN payer_name;
ALTER TABLE pix_transactions DROP COLUMN payer_phone;
ALTER TABLE pix_transactions DROP COLUMN payer_document;
