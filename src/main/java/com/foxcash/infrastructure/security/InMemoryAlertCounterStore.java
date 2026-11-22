package com.foxcash.infrastructure.security;

import com.foxcash.domain.port.out.AlertCounterStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Contador de rajada dos alertas **em memória** (Spec 66 — Fase B; default,
 * single-instância). Janela fixa por chave em {@link ConcurrentHashMap} — mesmo
 * comportamento do contador local do {@code SecurityAlertPublisher} (Spec 64),
 * extraído para a porta.
 *
 * <p>Produção multi-instância deve usar {@link RedisAlertCounterStore} via
 * {@code foxcash.security.alert.store=redis} — o limiar passa a ser **global**
 * (N réplicas somam; um flood distribuído não escapa do alerta).
 */
@Component
@ConditionalOnProperty(name = "foxcash.security.alert.store",
        havingValue = "memory", matchIfMissing = true)
public class InMemoryAlertCounterStore implements AlertCounterStore {

    private final Clock clock;
    private final ConcurrentHashMap<String, CounterState> counters = new ConcurrentHashMap<>();

    public InMemoryAlertCounterStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public long incrementAndFire(String counterKey, int threshold, long windowSeconds) {
        long now = clock.millis();
        CounterState state = counters.computeIfAbsent(counterKey, k -> new CounterState());
        boolean fire;
        synchronized (state) {
            if (state.windowStartMillis + windowSeconds * 1000L < now) {
                state.windowStartMillis = now;
                state.count.set(0);
            }
            long count = state.count.incrementAndGet();
            fire = count >= threshold;
            if (fire) {
                // Disparo único: reinicia a janela — a próxima rajada reacumula.
                state.count.set(0);
                state.windowStartMillis = now;
            }
        }
        return fire ? 1L : 0L;
    }

    /** Estado do contador de janela por chave. */
    private static class CounterState {
        private long windowStartMillis = 0L;
        private final AtomicLong count = new AtomicLong(0);
    }
}
