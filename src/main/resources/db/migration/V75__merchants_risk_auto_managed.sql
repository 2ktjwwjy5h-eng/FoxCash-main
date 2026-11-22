-- Spec 128 — Risk Scoring Engine (Gap 2): override manual vence.
--
-- risk_auto_managed = true → um humano (master) tocou config/status do lojista
-- (updateConfig/honeypot/deactivate/activate/ban/suspend/release — Specs 98/16)
-- → o engine automático NÃO age (nem reduz nem restaura o rate limit) — evita
-- guerra de edição. Sticky: o master desmarca manualmente (SQL/edição — sem UI
-- na v1). Default false — todo lojista nasce elegível ao automático.

ALTER TABLE merchants ADD COLUMN risk_auto_managed BOOLEAN NOT NULL DEFAULT false;
