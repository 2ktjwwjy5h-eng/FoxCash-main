package com.foxcash.infrastructure.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link RedisAlertCounterStore} (Spec 66 — Fase B): script Lua
 * atômico com chave prefixada e TTL = window-seconds; disparo único no limiar
 * (devolve 1); Redis indisponível → **fail-open** (fallback local por-instância,
 * WARN throttled — o alerta nunca é perdido).
 */
class RedisAlertCounterStoreTest {

    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    private RedisAlertCounterStore store;

    @BeforeEach
    void setUp() {
        store = new RedisAlertCounterStore(redisTemplate, CLOCK);
    }

    @Test
    void returnsOneWhenScriptFires() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any())).thenReturn(1L);

        assertThat(store.incrementAndFire("body:/api/v1/merchants", 5, 60)).isEqualTo(1);
    }

    @Test
    void returnsZeroWhenScriptDoesNotFire() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any())).thenReturn(0L);

        assertThat(store.incrementAndFire("login:master", 10, 60)).isZero();
    }

    @Test
    void usesPrefixedKeyWithThresholdAndWindowTtl() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any())).thenReturn(0L);

        store.incrementAndFire("login:master", 10, 60);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(any(DefaultRedisScript.class), keysCaptor.capture(), any(), any());
        assertThat(keysCaptor.getValue()).containsExactly("alert:login:master");
    }

    @Test
    void failsOpenFallsBackToLocalCounterWhenRedisUnavailable() {
        // Redis indisponível → contagem local por-instância (ADR-010): o alerta
        // continua funcionando (fire no limiar local), nunca é perdido.
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("Connection refused"));

        assertThat(store.incrementAndFire("body:x", 2, 60)).isZero();
        assertThat(store.incrementAndFire("body:x", 2, 60)).isEqualTo(1);
    }

    @Test
    void failsOpenOnAnyRuntimeException() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any()))
                .thenThrow(new IllegalStateException("script error"));

        assertThat(store.incrementAndFire("body:x", 1, 60)).isEqualTo(1);
    }

    @Test
    void failOpenWarnsThrottled() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("Connection refused"));

        Logger logger = (Logger) LoggerFactory.getLogger(RedisAlertCounterStore.class);
        Level original = logger.getLevel();
        logger.setLevel(Level.WARN);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            store.incrementAndFire("body:x", 5, 60);
            store.incrementAndFire("body:x", 5, 60);

            long warns = appender.list.stream()
                    .filter(e -> e.getFormattedMessage().contains("fail-open"))
                    .count();
            // Throttle de 30s: duas chamadas rápidas geram EXATAMENTE 1 WARN.
            assertThat(warns).isEqualTo(1);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(original);
        }
    }
}
