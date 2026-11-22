-- Spec 67 (!ip): lookup administrativo de lojista por endereço IP.
-- O comando !ip consulta pix_transactions.buyer_ip (IP do comprador na cobrança)
-- e platform_activity_log.ip (IP de origem do ator). Índices para o lookup:
CREATE INDEX IF NOT EXISTS ix_pix_transactions_buyer_ip
    ON pix_transactions (buyer_ip);
CREATE INDEX IF NOT EXISTS ix_platform_activity_ip
    ON platform_activity_log (ip);
