-- Provas de entrega (gateway-standard, 2026-08-06): evidências para disputas.
--
-- O que cada entrega passa a guardar além do que já existia (transaction_id,
-- created_at, platform_user_id, buyer_id_hash, delivered_at):
--   terms_accepted_at: data/hora do aceite dos termos pelo comprador (capturado
--     na compra do plano, POST /nyx/plans/{id}/purchase com termsAccepted=true);
--   buyer_ip: IP de origem do comprador no momento da compra (mesmo padrão da
--     trilha de auditoria — IP cru, truncado em 45 chars p/ IPv6);
--   buyer_device: User-Agent do dispositivo do comprador (truncado 255).
--
-- plan_delivery_access_logs: log de acesso ao conteúdo entregue (o bot registra
-- cada acesso real do comprador ao produto — prova de que a entrega foi usada).

ALTER TABLE pix_transactions
    ADD COLUMN terms_accepted_at TIMESTAMP,
    ADD COLUMN buyer_ip VARCHAR(45),
    ADD COLUMN buyer_device VARCHAR(255);

ALTER TABLE plan_deliveries
    ADD COLUMN terms_accepted_at TIMESTAMP,
    ADD COLUMN buyer_ip VARCHAR(45),
    ADD COLUMN buyer_device VARCHAR(255);

CREATE TABLE plan_delivery_access_logs (
    id UUID PRIMARY KEY,
    delivery_id UUID NOT NULL REFERENCES plan_deliveries(id),
    accessed_at TIMESTAMP NOT NULL,
    ip VARCHAR(45),
    device VARCHAR(255),
    note VARCHAR(255)
);
CREATE INDEX ix_plan_delivery_access_logs_delivery
    ON plan_delivery_access_logs (delivery_id, accessed_at);
