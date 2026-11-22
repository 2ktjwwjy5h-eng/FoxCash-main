-- Spec 79 (fix pós-E2E Spec 80) — CHECK de media_type em UPPERCASE.
--
-- A V54 criou `CHECK (media_type IN ('image','video','audio'))` em minúsculo,
-- mas o `BroadcastMediaType` (enum Java) é gravado via
-- `@Enumerated(EnumType.STRING)` — nome do enum em UPPERCASE (`IMAGE`/`VIDEO`/
-- `AUDIO`) —, o mesmo padrão do achado da Spec 74 (`store_bots.platform`:
-- o design dizia lowercase, mas o Hibernate grava o nome do enum). Qualquer
-- INSERT com mídia (upload do portal — Spec 80) violava a CHECK → 500
-- (DataIntegrityViolationException), nunca exercitado em teste unitário (sem
-- Postgres) nem no E2E da Spec 79 (não fazia upload).
--
-- Corrige a constraint EM LUGAR (aditiva, mesma migration): drop + add com os
-- valores reais gravados pelo enum.
ALTER TABLE broadcasts DROP CONSTRAINT IF EXISTS broadcasts_media_type_check;
ALTER TABLE broadcasts ADD CONSTRAINT broadcasts_media_type_check
    CHECK (media_type IN ('IMAGE','VIDEO','AUDIO'));
