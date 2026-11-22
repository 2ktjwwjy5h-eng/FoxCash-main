-- Spec 69 (F0/F3): step-up MFA em ações sensíveis do master.
-- Ação sensível exige stepped_up_at != null dentro da janela (default 15 min)
-- — sessão "elevada" por re-prova de posse (TOTP/recovery) via
-- POST /api/v1/master/mfa/step-up. Aditiva única: sem tabela nova
-- (master_permissions fica de fora — decisão do usuário 2026-08-11: concessão
-- por nível, derivada de can_manage_masters) e sem backfill/UPDATE.
ALTER TABLE master_sessions ADD COLUMN stepped_up_at TIMESTAMPTZ NULL;
