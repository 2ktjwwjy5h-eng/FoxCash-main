package com.foxcash.infrastructure.scheduler;

import com.foxcash.application.service.BroadcastDispatchService;
import com.foxcash.application.service.JobExecutionService;
import com.foxcash.infrastructure.config.BroadcastProperties;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Job de re-agendamento do broadcast REPOST (Spec 79/D10) — ShedLock +
 * {@link JobExecutionService} (Spec 53: métrica + trilha {@code job_runs} +
 * alerta {@code JOB_FAILED}). Re-agenda os REPOST em {@code COMPLETED}
 * ({@code scheduled_at = now + interval_seconds} → {@code PENDING}): o broadcast
 * permanece ativo até ser cancelado (DELETE de PENDING). Kill-switch
 * {@code foxcash.broadcast.repost-enabled}.
 */
@Component
public class BroadcastRepostScheduler {

    private final JobExecutionService jobs;
    private final BroadcastDispatchService dispatch;
    private final BroadcastProperties properties;

    public BroadcastRepostScheduler(JobExecutionService jobs,
                                    BroadcastDispatchService dispatch,
                                    BroadcastProperties properties) {
        this.jobs = jobs;
        this.dispatch = dispatch;
        this.properties = properties;
    }

    @Scheduled(cron = "${foxcash.broadcast.repost-cron:0 */5 * * * *}", zone = "UTC")
    @SchedulerLock(name = "foxcash-broadcast-repost", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1M")
    public void rescheduleReposts() {
        if (!properties.isRepostEnabled()) {
            return;
        }
        jobs.run("foxcash-broadcast-repost", dispatch::rescheduleCompletedReposts);
    }
}
