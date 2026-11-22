-- Spec 53: trilha auditavel de execucao de jobs (duracao/status/erro por job).
CREATE TABLE job_runs (
    id          BIGSERIAL PRIMARY KEY,
    job         VARCHAR(64)  NOT NULL,
    started_at  TIMESTAMP    NOT NULL,
    finished_at TIMESTAMP    NOT NULL,
    duration_ms BIGINT       NOT NULL,
    status      VARCHAR(16)  NOT NULL CHECK (status IN ('SUCCESS','FAILED')),
    error       TEXT
);
CREATE INDEX ix_job_runs_job_started ON job_runs (job, started_at DESC);

-- Spec 53: eventos de PLATAFORMA (ex.: JOB_FAILED, sem merchant) no outbox.
-- A FK para merchants permanece — apenas o NOT NULL cai (eventos de merchant intactos).
ALTER TABLE notification_outbox ALTER COLUMN merchant_id DROP NOT NULL;
