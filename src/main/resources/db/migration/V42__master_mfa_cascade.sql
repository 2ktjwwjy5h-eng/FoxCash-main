-- Spec 44 — correção E2E 2026-08-06: FKs de master_totp/master_recovery_codes
-- sem ON DELETE CASCADE. O MasterMfaService.deleteByMasterId (desativação e
-- descarte de método PENDING) apaga o método; os detalhes (secret TOTP e
-- recovery codes) devem ser removidos em cascata, senão a deleção viola a FK
-- (DataIntegrityViolationException → 500 em vez de 204).
ALTER TABLE master_totp
    DROP CONSTRAINT master_totp_method_id_fkey,
    ADD CONSTRAINT master_totp_method_id_fkey
        FOREIGN KEY (method_id) REFERENCES master_mfa_methods(id) ON DELETE CASCADE;

ALTER TABLE master_recovery_codes
    DROP CONSTRAINT master_recovery_codes_method_id_fkey,
    ADD CONSTRAINT master_recovery_codes_method_id_fkey
        FOREIGN KEY (method_id) REFERENCES master_mfa_methods(id) ON DELETE CASCADE;
