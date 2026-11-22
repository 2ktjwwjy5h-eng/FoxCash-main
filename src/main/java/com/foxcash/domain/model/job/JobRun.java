package com.foxcash.domain.model.job;

import java.time.Instant;
import java.util.UUID;

/**
 * Execucao de um job na trilha auditavel {@code job_runs} (Spec 53): nome do
 * job, janela de execucao, duracao, status (SUCCESS/FAILED) e erro (apenas em
 * falha, truncado). Gravado por TODA execucao — base para consulta/auditoria
 * operacional e para o job de limpeza (retencao configurável).
 */
public record JobRun(UUID id, String job, Instant startedAt, Instant finishedAt,
                     long durationMs, JobRunStatus status, String error) {

    public static JobRun success(String job, Instant startedAt, Instant finishedAt, long durationMs) {
        return new JobRun(UUID.randomUUID(), job, startedAt, finishedAt, durationMs,
                JobRunStatus.SUCCESS, null);
    }

    public static JobRun failed(String job, Instant startedAt, Instant finishedAt,
                                long durationMs, String error) {
        return new JobRun(UUID.randomUUID(), job, startedAt, finishedAt, durationMs,
                JobRunStatus.FAILED, error);
    }
}
