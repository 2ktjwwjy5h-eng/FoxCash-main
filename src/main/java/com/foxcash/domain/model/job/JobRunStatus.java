package com.foxcash.domain.model.job;

/**
 * Status de uma execucao de job na trilha auditavel {@code job_runs} (Spec 53).
 */
public enum JobRunStatus {
    SUCCESS,
    FAILED
}
