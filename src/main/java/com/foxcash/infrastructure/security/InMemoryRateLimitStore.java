package com.foxcash.infrastructure.security;

import com.foxcash.domain.port.out.RateLimitStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Store do rate limit **em memória** (Spec 18/ADR-009 — topologia atual
 * single-instance; default). Janela fixa por chave (IP+rota) em
 * {@link ConcurrentHashMap} com limpeza best-effort quando o mapa cresce.
 *
 * <p>Produção multi-instância deve usar {@link RedisRateLimitStore} (Spec 43 —
 * M-05) via {@code foxcash.security.rate-limit.store=redis}.
 */
@Component
@ConditionalOnProperty(name = "foxcash.security.rate-limit.store",
        havingValue = "memory", matchIfMissing = true)
public class InMemoryRateLimitStore implements RateLimitStore {

    /** Guarda de crescimento do mapa (limpeza best-effort quando grande). */
    private static final int MAX_ENTRIES = 10_000;

    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    record Window(long windowStartMillis, int count) {
    }

    public InMemoryRateLimitStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean tryAcquire(String key, int maxRequests, long windowSeconds) {
        long now = clock.millis();
        long windowMs = windowSeconds * 1000L;

        if (windows.size() > MAX_ENTRIES) {
            windows.entrySet().removeIf(e -> now - e.getValue().windowStartMillis() >= windowMs);
        }

        Window current = windows.compute(key, (k, w) -> {
            if (w == null || now - w.windowStartMillis() >= windowMs) {
                return new Window(now, 1);
            }
            return new Window(w.windowStartMillis(), w.count() + 1);
        });

        return current.count() <= maxRequests;
    }

    /** Exposição p/ testes: janela atual da chave (null se nunca contada). */
    Window window(String key) {
        return windows.get(key);
    }
}
