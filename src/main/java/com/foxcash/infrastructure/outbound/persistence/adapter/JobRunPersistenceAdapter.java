package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.job.JobRun;
import com.foxcash.domain.port.out.JobRunRepository;
import com.foxcash.infrastructure.outbound.persistence.entity.JobRunJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.repository.JobRunJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Adaptador JPA do port {@link JobRunRepository} (Spec 53) — trilha auditavel
 * de execucoes de jobs. Grava em transacao propria e apaga registros antigos
 * (job de limpeza diario).
 */
@Repository
public class JobRunPersistenceAdapter implements JobRunRepository {

    private final JobRunJpaRepository jpaRepository;

    public JobRunPersistenceAdapter(JobRunJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public void save(JobRun run) {
        jpaRepository.save(new JobRunJpaEntity(
                run.job(), run.startedAt(), run.finishedAt(),
                run.durationMs(), run.status(), run.error()));
    }

    @Override
    @Transactional
    public int deleteOlderThan(Instant before) {
        return jpaRepository.deleteOlderThan(before);
    }
}
