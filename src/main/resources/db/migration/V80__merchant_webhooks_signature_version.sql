-- Spec 148: versionamento da assinatura do webhook de saida.
--
-- O formato do header X-FoxCash-Signature passa a ser versionado por lojista:
--   v2 (default) — t=<unix>,v1=<hex> (HMAC de "<t>.<rawBody>", anti-replay)
--   v1 (legado)  — hex puro do HMAC do raw body (Spec 35)
--
-- Janela de transicao de 90 dias: v1 continua aceito/emitido para registros
-- que ainda nao migraram; ao fim, v1 e removido (decisao documentada na Spec
-- 138/docs/webhooks.md).
--
-- Backfill: registros existentes nascem com v2 (novo default) — o receptor
-- dos bots white-label ja valida v1 E v2 nesta janela (Spec 150), sem quebra.

ALTER TABLE merchant_webhooks
    ADD COLUMN signature_version VARCHAR(10) NOT NULL DEFAULT 'v2';
