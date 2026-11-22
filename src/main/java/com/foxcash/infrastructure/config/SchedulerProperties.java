package com.foxcash.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracao da execucao de jobs (Spec 53): pool de threads do scheduler e
 * retencao da trilha auditavel {@code job_runs}.
 *
 * <p>Os crons existentes ({@code foxcash.scheduler.invoice-cron} etc.) sao lidos
 * via SpEL nos {@code @Scheduled} e nao sao boundados aqui — esta classe cobre
 * apenas as propriedades NOVAS da Spec 53 (pool-size, job-runs.*,
 * job-runs-cleanup-cron).
 */
@ConfigurationProperties("foxcash.scheduler")
public class SchedulerProperties {

    /** Tamanho do pool de threads dos jobs (default 4). Antes: pool default do Spring (1 thread). */
    private int poolSize = 4;

    /** Retencao da trilha {@code job_runs} (Spec 53). */
    private JobRuns jobRuns = new JobRuns();

    /** Cron do job de limpeza da trilha (UTC, default 04:45 — apos os crons). */
    private String jobRunsCleanupCron = "0 45 4 * * *";

    /** Retencao da trilha {@code platform_activity_log} (Spec 95). */
    private ActivityLog activityLog = new ActivityLog();

    public int getPoolSize() {
        return poolSize;
    }

    public void setPoolSize(int poolSize) {
        this.poolSize = poolSize;
    }

    public JobRuns getJobRuns() {
        return jobRuns;
    }

    public void setJobRuns(JobRuns jobRuns) {
        this.jobRuns = jobRuns;
    }

    public String getJobRunsCleanupCron() {
        return jobRunsCleanupCron;
    }

    public void setJobRunsCleanupCron(String jobRunsCleanupCron) {
        this.jobRunsCleanupCron = jobRunsCleanupCron;
    }

    public ActivityLog getActivityLog() {
        return activityLog;
    }

    public void setActivityLog(ActivityLog activityLog) {
        this.activityLog = activityLog;
    }

    /** Bloco {@code foxcash.scheduler.job-runs.*} (Spec 53). */
    public static class JobRuns {

        /** Registros mais antigos que isso (dias) sao apagados pelo cleanup diario. */
        private int retentionDays = 30;

        public int getRetentionDays() {
            return retentionDays;
        }

        public void setRetentionDays(int retentionDays) {
            this.retentionDays = retentionDays;
        }
    }

    /**
     * Bloco {@code foxcash.scheduler.activity-log.*} (Spec 95) — retencao da
     * trilha {@code platform_activity_log}. O job é opt-in
     * ({@code foxcash.scheduler.activity-log-cleanup-enabled=true}); desligado
     * por default, o contrato append-only da Spec 41 permanece.
     */
    public static class ActivityLog {

        /** Eventos mais antigos que isso (dias) sao apagados quando o job está ativo. */
        private int retentionDays = 180;

        public int getRetentionDays() {
            return retentionDays;
        }

        public void setRetentionDays(int retentionDays) {
            this.retentionDays = retentionDays;
        }
    }
}
