package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.job.JobRun;
import com.foxcash.domain.model.job.JobRunStatus;
import com.foxcash.infrastructure.outbound.persistence.entity.JobRunJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.repository.JobRunJpaRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do adapter JPA da trilha {@code job_runs} (Spec 53): mapeia o domínio
 * para a entidade no save e delega o DELETE por retenção ao repositório.
 */
class JobRunPersistenceAdapterTest {

    private final JobRunJpaRepository jpaRepository = mock(JobRunJpaRepository.class);
    private final JobRunPersistenceAdapter adapter = new JobRunPersistenceAdapter(jpaRepository);

    @Test
    void saveMapsJobRunToEntity() {
        Instant startedAt = Instant.parse("2026-08-07T00:00:00Z");
        Instant finishedAt = Instant.parse("2026-08-07T00:00:05Z");
        JobRun run = JobRun.success("foxcash-test", startedAt, finishedAt, 5_000);

        adapter.save(run);

        ArgumentCaptor<JobRunJpaEntity> captor = ArgumentCaptor.forClass(JobRunJpaEntity.class);
        verify(jpaRepository).save(captor.capture());
        JobRunJpaEntity entity = captor.getValue();
        assertThat(entity.getJob()).isEqualTo("foxcash-test");
        assertThat(entity.getStartedAt()).isEqualTo(startedAt);
        assertThat(entity.getFinishedAt()).isEqualTo(finishedAt);
        assertThat(entity.getDurationMs()).isEqualTo(5_000);
        assertThat(entity.getStatus()).isEqualTo(JobRunStatus.SUCCESS);
        assertThat(entity.getError()).isNull();
    }

    @Test
    void failedRunCarriesError() {
        JobRun run = JobRun.failed("foxcash-test", Instant.now(), Instant.now(), 10, "boom");

        adapter.save(run);

        ArgumentCaptor<JobRunJpaEntity> captor = ArgumentCaptor.forClass(JobRunJpaEntity.class);
        verify(jpaRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(JobRunStatus.FAILED);
        assertThat(captor.getValue().getError()).isEqualTo("boom");
    }

    @Test
    void deleteOlderThanDelegatesToRepository() {
        Instant before = Instant.parse("2026-08-01T00:00:00Z");
        when(jpaRepository.deleteOlderThan(before)).thenReturn(3);

        int deleted = adapter.deleteOlderThan(before);

        assertThat(deleted).isEqualTo(3);
        verify(jpaRepository).deleteOlderThan(before);
    }
}
