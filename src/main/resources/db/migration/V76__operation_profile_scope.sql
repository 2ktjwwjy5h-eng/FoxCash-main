-- Spec 139 — Escopo do perfil de operação (GENERAL/BOT_ONLY/GATEWAY_ONLY).
--
-- O perfil (Spec 122) nasce com um escopo que classifica a operação:
--   GENERAL      — ⚡ Completo (rota de gateway + frota de bots)
--   BOT_ONLY     — 🤖 Somente Bot (frota; rota sempre por risco — null)
--   GATEWAY_ONLY — 🌐 Somente Gateway (rota; frota vazia)
-- O backfill é implícito: DEFAULT 'GENERAL' aplica a todos os perfis
-- existentes (idempotente por natureza — sem UPDATE explícito). A constraint
-- CHECK garante os 3 valores no banco (defesa em profundidade).
--
-- store_bots inalterada: profile_id continua a âncora da frota (Spec 74).

ALTER TABLE merchant_profiles
    ADD COLUMN scope VARCHAR(20) NOT NULL DEFAULT 'GENERAL';

ALTER TABLE merchant_profiles
    ADD CONSTRAINT chk_merchant_profiles_scope
    CHECK (scope IN ('GENERAL', 'BOT_ONLY', 'GATEWAY_ONLY'));
