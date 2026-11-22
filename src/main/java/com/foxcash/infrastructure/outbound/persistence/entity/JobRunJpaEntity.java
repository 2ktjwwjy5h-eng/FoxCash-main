package com.foxcash.infrastructure.outbound.persistence.entity;

import com.foxcash.domain.model.job.JobRunStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Entidade JPA da trilha auditavel de execucoes de jobs (Spec 53).
 * {@code id} BIGSERIAL (identity) — append-only; a limpeza e feita pelo job
 * diario ({@code foxcash.scheduler.job-runs-cleanup-cron}) por retencao.
 */
@Entity
@Table(name = "job_runs",
        indexes = {
                @Index(name = "ix_job_runs_job_started", columnList = "job, started_at DESC")
        })
public class JobRunJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String job;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at", nullable = false)
    private Instant finishedAt;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private JobRunStatus status;

    @Column(columnDefinition = "text")
    private String error;

    protected JobRunJpaEntity() {
    }

    public JobRunJpaEntity(String job, Instant startedAt, Instant finishedAt,
                           long durationMs, JobRunStatus status, String error) {
        this.job = job;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.durationMs = durationMs;
        this.status = status;
        this.error = error;
    }

    public Long getId() {
        return id;
    }

    public String getJob() {
        return job;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public JobRunStatus getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }
}
