-- Spec 124 (fase 2 — filtro rígido por perfil): perfil de operação da transação.
-- V69 (Spec 122) cria merchant_profiles + store_bots.profile_id; aqui a transação
-- passa a carregar o perfil para o escopo do portal (dashboard/sales por perfil)
-- e a "rota viva" (a cobrança grava o perfil que decidiu o roteamento).
--
-- Semântica de escopo (fonte da verdade = banco, nunca header cego):
--   profile_id NULL  -> transação não-escopada (visível só no escopo "sem header" —
--                       lojista inteiro; dado legado anterior à V69).
--   profile_id set   -> pertence àquele perfil (filtro rígido do portal).

ALTER TABLE pix_transactions ADD COLUMN profile_id UUID REFERENCES merchant_profiles (id);

-- Índice parcial do escopo por perfil (portal: dashboard/sales por merchant+profile).
CREATE INDEX ix_pix_transactions_profile
    ON pix_transactions (merchant_id, profile_id) WHERE profile_id IS NOT NULL;

-- Backfill: transação com bot → perfil do bot (store_bots.profile_id, V69);
-- transação sem bot (avulsa/API legada) → perfil default do lojista;
-- lojista sem perfil (dado anterior à V69) → fica NULL (não-escopada).
UPDATE pix_transactions t
   SET profile_id = b.profile_id
  FROM store_bots b
 WHERE t.bot_id = b.id AND b.profile_id IS NOT NULL;

UPDATE pix_transactions t
   SET profile_id = mp.id
  FROM merchant_profiles mp
 WHERE t.profile_id IS NULL
   AND t.merchant_id = mp.merchant_id
   AND mp.is_default;
