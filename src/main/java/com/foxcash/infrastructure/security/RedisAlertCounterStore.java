package com.foxcash.infrastructure.security;

import com.foxcash.domain.port.out.AlertCounterStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Contador de rajada dos alertas **agregado via Redis** (Spec 66 — Fase B).
 *
 * <p>Janela fixa por chave com script Lua **atômico** — {@code INCR} +
 * {@code EXPIRE} (TTL = {@code window-seconds}, setado apenas na 1ª contagem da
 * janela) e **disparo único**: quando o contador atinge o limiar, o script
 * apaga a chave (reinicia a janela) e devolve 1 — consistente entre instâncias:
 * N bloqueios distribuídos entre N pods somam no MESMO contador (limiar
 * **global**, sem corrida).
 *
 * <p><b>Fail-open (ADR-010):</b> se o Redis estiver indisponível,
 * {@code incrementAndFire} cai para o **contador local em memória** (WARN
 * throttled 30s) — o alerta continua por-instância; o bloqueio nunca é perdido
 * nem o caminho degradado (a contagem agregada só é vista quando o Redis volta).
 *
 * <p>Ativado por {@code foxcash.security.alert.store=redis} (default
 * {@code memory} — single-instance sem Redis).
 */
@Component
@ConditionalOnProperty(name = "foxcash.security.alert.store",
        havingValue = "redis", matchIfMissing = false)
public class RedisAlertCounterStore implements AlertCounterStore {

    private static final Logger log = LoggerFactory.getLogger(RedisAlertCounterStore.class);

    private static final String KEY_PREFIX = "alert:";

    /** Script atômico: INCR (EXPIRE na 1ª contagem da janela); se count ==
     * threshold → DEL (reinicia a janela, disparo único) e devolve 1; senão 0. */
    private static final DefaultRedisScript<Long> INCR_FIRE_SCRIPT = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1])\n"
                    + "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end\n"
                    + "if c == tonumber(ARGV[1]) then\n"
                    + "  redis.call('DEL', KEYS[1])\n"
                    + "  return 1\n"
                    + "end\n"
                    + "return 0",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    /** Fallback local (fail-open) — contador por instância quando o Redis cai. */
    private final ConcurrentHashMap<String, CounterState> fallback = new ConcurrentHashMap<>();

    /** Instante do último WARN de fail-open (throttle: 1 a cada 30s). */
    private volatile long lastWarnAtMillis = 0L;

    public RedisAlertCounterStore(StringRedisTemplate redisTemplate, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
    }

    @Override
    public long incrementAndFire(String counterKey, int threshold, long windowSeconds) {
        try {
            Long fired = redisTemplate.execute(INCR_FIRE_SCRIPT,
                    List.of(KEY_PREFIX + counterKey),
                    String.valueOf(threshold), String.valueOf(windowSeconds));
            return fired == null ? 0L : fired;
        } catch (RuntimeException e) {
            // Fail-open (ADR-010): Redis indisponível → contagem por-instância
            // (o alerta nunca é perdido). Log throttled para não inundar.
            warnThrottled("Alert counter Redis indisponivel — fail-open (contagem por instancia): "
                    + e.getMessage());
            return incrementAndFireLocal(counterKey, threshold, windowSeconds);
        }
    }

    /** Fallback local — espelha o {@link InMemoryAlertCounterStore}. */
    private long incrementAndFireLocal(String counterKey, int threshold, long windowSeconds) {
        long now = clock.millis();
        CounterState state = fallback.computeIfAbsent(counterKey, k -> new CounterState());
        boolean fire;
        synchronized (state) {
            if (state.windowStartMillis + windowSeconds * 1000L < now) {
                state.windowStartMillis = now;
                state.count.set(0);
            }
            long count = state.count.incrementAndGet();
            fire = count >= threshold;
            if (fire) {
                state.count.set(0);
                state.windowStartMillis = now;
            }
        }
        return fire ? 1L : 0L;
    }

    private void warnThrottled(String message) {
        long now = System.currentTimeMillis();
        if (now - lastWarnAtMillis > 30_000L) {
            lastWarnAtMillis = now;
            log.warn(message);
        }
    }

    /** Estado do contador de janela por chave (fallback local). */
    private static class CounterState {
        private long windowStartMillis = 0L;
        private final AtomicLong count = new AtomicLong(0);
    }
}
