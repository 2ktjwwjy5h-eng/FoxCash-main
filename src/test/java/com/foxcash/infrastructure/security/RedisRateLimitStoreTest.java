package com.foxcash.infrastructure.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.foxcash.application.service.SecurityAlertPublisher;
import com.foxcash.domain.port.out.RateLimitStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link RedisRateLimitStore} (Spec 43 — M-05): contador atômico via
 * script Lua (INCR+EXPIRE) com chave prefixada e TTL = window-seconds; acima do
 * limite → {@code false} (429 no filtro); Redis indisponível → **fail-open**
 * (permite a requisição).
 */
class RedisRateLimitStoreTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final SecurityAlertPublisher securityAlerts = mock(SecurityAlertPublisher.class);

    private RateLimitStore store;

    @BeforeEach
    void setUp() {
        store = new RedisRateLimitStore(redisTemplate, securityAlerts);
    }

    @Test
    void allowsWhenCounterWithinLimit() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any())).thenReturn(2L);

        assertThat(store.tryAcquire("rota|ip", 2, 60)).isTrue();
    }

    @Test
    void blocksWhenCounterExceedsLimit() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any())).thenReturn(3L);

        assertThat(store.tryAcquire("rota|ip", 2, 60)).isFalse();
    }

    @Test
    void usesPrefixedKeyWithWindowTtl() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any())).thenReturn(1L);

        store.tryAcquire("rota|ip", 2, 60);

        // Chave prefixada (rl:) e TTL = window-seconds (60) como ARGV.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(any(DefaultRedisScript.class), keysCaptor.capture(), any());
        assertThat(keysCaptor.getValue()).containsExactly("rl:rota|ip");
    }

    @Test
    void failsOpenWhenRedisIsUnavailable() {
        // Redis indisponível → permite a requisição (ADR-010: o gateway não para
        // por falha de infra auxiliar; a limitação é proteção secundária).
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any()))
                .thenThrow(new DataAccessResourceFailureException("Connection refused"));

        assertThat(store.tryAcquire("rota|ip", 2, 60)).isTrue();
    }

    @Test
    void failsOpenOnAnyRuntimeException() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any()))
                .thenThrow(new IllegalStateException("script error"));

        assertThat(store.tryAcquire("rota|ip", 2, 60)).isTrue();
    }

    @Test
    void failOpenWarnsOncePerWindow() {
        // L64/L72/L73: warnThrottled é chamado no fail-open e o WARN é throttled
        // (1 por janela) — mutantes de Math (divisão/soma), negate ou remoção da
        // chamada mudariam a contagem de WARNs.
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any()))
                .thenThrow(new DataAccessResourceFailureException("Connection refused"));

        Logger logger = (Logger) LoggerFactory.getLogger(RedisRateLimitStore.class);
        Level original = logger.getLevel();
        logger.setLevel(Level.WARN);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(store.tryAcquire("rota|ip", 2, 1)).isTrue();
            assertThat(store.tryAcquire("rota|ip", 2, 1)).isTrue();

            long warns = appender.list.stream()
                    .filter(e -> e.getFormattedMessage().contains("fail-open"))
                    .count();
            // Throttle de 1s: duas chamadas rápidas geram EXATAMENTE 1 WARN.
            assertThat(warns).isEqualTo(1);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(original);
        }
    }

    @Test
    void failOpenRecordsSecurityAlert() {
        // Spec 64: fail-open ativado → alerta de segurança (throttled no publisher).
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any()))
                .thenThrow(new DataAccessResourceFailureException("Connection refused"));

        assertThat(store.tryAcquire("rota|ip", 2, 60)).isTrue();

        verify(securityAlerts).recordRateLimitFailOpen("redis-indisponivel");
    }
}
