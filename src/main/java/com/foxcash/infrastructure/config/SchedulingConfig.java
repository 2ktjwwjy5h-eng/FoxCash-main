package com.foxcash.infrastructure.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * Habilita o agendamento ({@code @Scheduled}) e o ShedLock, que garante
 * execucao unica de jobs mesmo com multiplas instancias (lock via tabela
 * {@code shedlock} no PostgreSQL).
 *
 * <p>Spec 53: pool de threads dedicado aos jobs ({@code foxcash-job-*}) — antes o
 * pool default do Spring tinha 1 thread, e um cron longo bloqueava o poll do
 * outbox (30s) e vice-versa. Tamanho configurável via
 * {@code foxcash.scheduler.pool-size} (default 4).
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class SchedulingConfig {

    private static final Logger log = LoggerFactory.getLogger(SchedulingConfig.class);

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName("shedlock")
                .usingDbTime()
                .build());
    }

    /**
     * Diagnostico de clock skew (Etapa 1, pos-incidente Fase 2A 2026-08-09):
     * loga o delta entre o relogio da app e o do banco no boot. O ShedLock usa
     * {@code usingDbTime()} (relogio do banco), entao o skew das instancias NAO
     * afeta a aquisicao de lock — o probe da evidencia para corrigir o relogio
     * dos hosts (Railway). Nunca bloqueia o boot (fail-open).
     */
    @Bean
    public ApplicationRunner clockSkewProbe(DataSource dataSource) {
        return args -> {
            try {
                JdbcTemplate jdbc = new JdbcTemplate(dataSource);
                Timestamp dbNow = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
                Instant appNow = Instant.now();
                long deltaSeconds = Duration.between(dbNow.toInstant(), appNow).getSeconds();
                log.info("ClockSkewProbe: app_now={} db_now={} delta={}s (app-db; |delta|>60 = clock skew "
                        + "a investigar)", appNow, dbNow.toInstant(), deltaSeconds);
            } catch (Exception e) {
                log.warn("ClockSkewProbe indisponivel no boot (nao bloqueia): {}", e.getMessage());
            }
        };
    }

    /**
     * Pool de threads dos jobs (Spec 53). Substitui o scheduler default do
     * Spring Boot (1 thread): {@code @Scheduled} passa a rodar com
     * {@code foxcash.scheduler.pool-size} threads. Graceful shutdown — deploy
     * nao aborta um job no meio.
     */
    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler(SchedulerProperties properties) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(properties.getPoolSize());
        scheduler.setThreadNamePrefix("foxcash-job-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
