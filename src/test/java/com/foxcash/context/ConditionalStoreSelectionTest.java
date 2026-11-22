package com.foxcash.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.application.service.SecurityAlertPublisher;
import com.foxcash.domain.port.out.AlertCounterStore;
import com.foxcash.domain.port.out.RateLimitStore;
import com.foxcash.infrastructure.security.BodyInspectionProperties;
import com.foxcash.infrastructure.security.InMemoryAlertCounterStore;
import com.foxcash.infrastructure.security.InMemoryRateLimitStore;
import com.foxcash.infrastructure.security.RedisAlertCounterStore;
import com.foxcash.infrastructure.security.RedisRateLimitStore;
import com.foxcash.infrastructure.security.RequestBodyInspectionFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Camada 2 — seleção condicional de beans (Spec 68).
 *
 * <p>Valida com {@link ApplicationContextRunner} (sem subir a app nem tocar em
 * banco) a seleção dos stores por {@code @ConditionalOnProperty}:
 * {@code foxcash.security.alert.store} e {@code foxcash.security.rate-limit.store}
 * em 4 combinações (memory explícito / redis / ausente → default / inválido →
 * nenhum bean → falha no consumidor) + o desligamento do body inspection não
 * remove o filtro do contexto.
 */
class ConditionalStoreSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StoresConfig.class);

    /** Registra os 4 stores condicionais + as dependências de construção. */
    @Configuration
    @Import({InMemoryAlertCounterStore.class, RedisAlertCounterStore.class,
            InMemoryRateLimitStore.class, RedisRateLimitStore.class})
    static class StoresConfig {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return new StringRedisTemplate(mock(RedisConnectionFactory.class));
        }

        /** O publisher real exige WebhookEventPublisher/TransactionManager etc.
         * — para o teste de SELEÇÃO dos stores, um mock basta (o Redis store só
         * chama recordRateLimitFailOpen no fail-open). */
        @Bean
        SecurityAlertPublisher securityAlertPublisher() {
            return mock(SecurityAlertPublisher.class);
        }
    }

    /** Consumidor que injeta {@link AlertCounterStore} — prova o fail-fast por
     * bean ausente quando o valor do store é inválido. */
    @Configuration
    @Import(StoresConfig.class)
    static class WithAlertConsumerConfig {

        @Bean
        AlertConsumer alertConsumer(AlertCounterStore store) {
            return new AlertConsumer(store);
        }
    }

    /** Consumidor do body inspection — prova que o filtro fica no contexto
     * mesmo com {@code body-inspection.enabled=false} (o desligamento é por
     * {@code shouldNotFilter}, não por bean ausente). */
    @Configuration
    @Import(StoresConfig.class)
    static class WithBodyFilterConfig {

        @Bean
        BodyInspectionProperties bodyInspectionProperties() {
            return new BodyInspectionProperties();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        io.micrometer.core.instrument.MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        RequestBodyInspectionFilter bodyInspectionFilter(BodyInspectionProperties p,
                                                          ObjectMapper om,
                                                          SecurityAlertPublisher alerts,
                                                          io.micrometer.core.instrument.MeterRegistry meters) {
            return new RequestBodyInspectionFilter(p, om, alerts, meters);
        }
    }

    static class AlertConsumer {
        private final AlertCounterStore store;

        AlertConsumer(AlertCounterStore store) {
            this.store = store;
        }

        AlertCounterStore store() {
            return store;
        }
    }

    // ---- foxcash.security.alert.store ----

    @Test
    void alertStoreAbsentDefaultsToInMemory() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(InMemoryAlertCounterStore.class);
            assertThat(ctx).doesNotHaveBean(RedisAlertCounterStore.class);
        });
    }

    @Test
    void alertStoreExplicitMemorySelectsInMemory() {
        runner.withPropertyValues("foxcash.security.alert.store=memory").run(ctx -> {
            assertThat(ctx).hasSingleBean(InMemoryAlertCounterStore.class);
            assertThat(ctx).doesNotHaveBean(RedisAlertCounterStore.class);
        });
    }

    @Test
    void alertStoreRedisSelectsRedisOnly() {
        runner.withPropertyValues("foxcash.security.alert.store=redis").run(ctx -> {
            assertThat(ctx).hasSingleBean(RedisAlertCounterStore.class);
            assertThat(ctx).doesNotHaveBean(InMemoryAlertCounterStore.class);
        });
    }

    @Test
    void alertStoreInvalidValueFailsWhenConsumed() {
        new ApplicationContextRunner()
                .withUserConfiguration(WithAlertConsumerConfig.class)
                .withPropertyValues("foxcash.security.alert.store=xxx")
                .run(ctx -> {
                    Throwable failure = ctx.getStartupFailure();
                    assertThat(failure)
                            .as("valor inválido → nenhum AlertCounterStore → consumidor falha")
                            .isNotNull();
                    assertThat(causeChainMessages(failure))
                            .anySatisfy(m -> assertThat(m).contains("AlertCounterStore"));
                });
    }

    // ---- foxcash.security.rate-limit.store ----

    @Test
    void rateLimitStoreAbsentDefaultsToInMemory() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(InMemoryRateLimitStore.class);
            assertThat(ctx).doesNotHaveBean(RedisRateLimitStore.class);
        });
    }

    @Test
    void rateLimitStoreExplicitMemorySelectsInMemory() {
        runner.withPropertyValues("foxcash.security.rate-limit.store=memory").run(ctx -> {
            assertThat(ctx).hasSingleBean(InMemoryRateLimitStore.class);
            assertThat(ctx).doesNotHaveBean(RedisRateLimitStore.class);
        });
    }

    @Test
    void rateLimitStoreRedisSelectsRedisOnly() {
        runner.withPropertyValues("foxcash.security.rate-limit.store=redis").run(ctx -> {
            assertThat(ctx).hasSingleBean(RedisRateLimitStore.class);
            assertThat(ctx).doesNotHaveBean(InMemoryRateLimitStore.class);
        });
    }

    @Test
    void rateLimitStoreInvalidValueFailsWhenConsumed() {
        new ApplicationContextRunner()
                .withUserConfiguration(WithRateLimitConsumerConfig.class)
                .withPropertyValues("foxcash.security.rate-limit.store=xxx")
                .run(ctx -> {
                    Throwable failure = ctx.getStartupFailure();
                    assertThat(failure)
                            .as("valor inválido → nenhum RateLimitStore → consumidor falha")
                            .isNotNull();
                    assertThat(causeChainMessages(failure))
                            .anySatisfy(m -> assertThat(m).contains("RateLimitStore"));
                });
    }

    @Configuration
    @Import(StoresConfig.class)
    static class WithRateLimitConsumerConfig {

        @Bean
        RateLimitConsumer rateLimitConsumer(RateLimitStore store) {
            return new RateLimitConsumer(store);
        }
    }

    static class RateLimitConsumer {
        private final RateLimitStore store;

        RateLimitConsumer(RateLimitStore store) {
            this.store = store;
        }
    }

    // ---- body inspection ----

    @Test
    void bodyInspectionDisabledKeepsFilterBean() {
        new ApplicationContextRunner()
                .withUserConfiguration(WithBodyFilterConfig.class)
                .withPropertyValues("foxcash.security.body-inspection.enabled=false")
                .run(ctx ->
                        assertThat(ctx).hasSingleBean(RequestBodyInspectionFilter.class));
    }

    // ---- helpers ----

    private static List<String> causeChainMessages(Throwable t) {
        java.util.ArrayList<String> messages = new java.util.ArrayList<>();
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c.getMessage() != null) {
                messages.add(c.getMessage());
            }
        }
        return messages;
    }
}
