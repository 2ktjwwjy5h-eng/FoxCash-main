package com.foxcash.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do pool de threads dos jobs (Spec 53): tamanho aplicado do
 * {@code foxcash.scheduler.pool-size} e prefixo {@code foxcash-job-}. (Graceful
 * shutdown e definido via setters do {@code ExecutorConfigurationSupport} —
 * sem getters publicos para leitura.)
 */
class SchedulingConfigTest {

    @Test
    void taskSchedulerUsesConfiguredPoolSizeAndPrefix() {
        SchedulerProperties properties = new SchedulerProperties();
        properties.setPoolSize(6);

        ThreadPoolTaskScheduler scheduler = new SchedulingConfig().taskScheduler(properties);

        assertThat(scheduler.getPoolSize()).isEqualTo(6);
        assertThat(scheduler.getThreadNamePrefix()).isEqualTo("foxcash-job-");
    }

    @Test
    void defaultPoolSizeIsFour() {
        ThreadPoolTaskScheduler scheduler =
                new SchedulingConfig().taskScheduler(new SchedulerProperties());

        assertThat(scheduler.getPoolSize()).isEqualTo(4);
        assertThat(scheduler.getThreadNamePrefix()).isEqualTo("foxcash-job-");
    }
}
