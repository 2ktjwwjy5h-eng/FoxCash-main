-- Spec 74 follow-up (auditoria 2026-08-18): token independente do header do
-- Telegram (X-Telegram-Bot-Api-Secret-Token), separado do segredo da URL.
--
-- Antes: o MESMO segredo compunha a URL /webhooks/telegram/{id}/{secret} e o
-- secret_token registrado no setWebhook. Quem vazasse a URL tinha o header de
-- graça. Agora o header usa um segredo independente (32 bytes hex, só o hash
-- SHA-256 persistido).
--
-- Backfill: bots existentes tinham o webhook registrado com
-- secret_token = <url_secret> no Telegram. Para não quebrar a entrega até a
-- re-integração (register/resume/reconcile geram token novo e re-registram o
-- webhook), o hash do token é inicializado com o hash do url_secret — o header
-- atual (url_secret) continua válido, e o endpoint compara o sha256 do header
-- contra esse hash.

ALTER TABLE store_bots
    ADD COLUMN webhook_secret_token_hash VARCHAR(64);

UPDATE store_bots
SET webhook_secret_token_hash = webhook_secret_hash
WHERE webhook_secret_token_hash IS NULL
  AND deleted_at IS NULL;
