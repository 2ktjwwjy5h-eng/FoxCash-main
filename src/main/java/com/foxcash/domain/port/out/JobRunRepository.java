package com.foxcash.domain.port.out;

import com.foxcash.domain.model.job.JobRun;

import java.time.Instant;

/**
 * Porta de saida: trilha auditavel de execucoes de jobs (Spec 53).
 * {@code save} grava SUCCESS/FAILED; {@code deleteOlderThan} apaga registros
 * antigos (job de limpeza diario, retencao configurável).
 */
public interface JobRunRepository {

    void save(JobRun run);

    int deleteOlderThan(Instant before);
}
