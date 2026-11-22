package com.foxcash.infrastructure.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do {@link InMemoryRateLimitStore} (Spec 43 — M-05): janela fixa por
 * chave com limpeza best-effort; semântica idêntica à versão original do
 * {@code RateLimitFilter} (Spec 18/ADR-009).
 */
class InMemoryRateLimitStoreTest {

    /** Relógio mutável p/ teste de boundary exato (matar ConditionalsBoundary L36/L40). */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(long millis) {
            now = now.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock(Instant.parse("2026-08-06T12:00:00Z"));

    private final InMemoryRateLimitStore store = new InMemoryRateLimitStore(clock);

    @Test
    void allowsUpToLimitThenBlocks() {
        assertThat(store.tryAcquire("rota|ip", 2, 60)).isTrue();
        assertThat(store.tryAcquire("rota|ip", 2, 60)).isTrue();
        assertThat(store.tryAcquire("rota|ip", 2, 60)).isFalse();
    }

    @Test
    void keysAreIsolated() {
        assertThat(store.tryAcquire("rota-a|ip", 1, 60)).isTrue();
        assertThat(store.tryAcquire("rota-b|ip", 1, 60)).isTrue();
        // Mesma rota, outro IP → janela própria.
        assertThat(store.tryAcquire("rota-a|outro-ip", 1, 60)).isTrue();
        // A primeira chave já estourou.
        assertThat(store.tryAcquire("rota-a|ip", 1, 60)).isFalse();
    }

    @Test
    void windowExpiresAfterWindowSeconds() {
        assertThat(store.tryAcquire("rota|ip", 1, 60)).isTrue();
        assertThat(store.tryAcquire("rota|ip", 1, 60)).isFalse();

        // Janela pequena (1s): após expirar, a chave volta a contar do zero.
        clock.advance(1_100);
        assertThat(store.tryAcquire("rota|ip", 1, 1)).isTrue();
        assertThat(store.tryAcquire("rota|ip", 1, 1)).isFalse();
    }

    @Test
    void cleanupBestEffortRemovesExpiredEntriesWhenMapGrows() {
        // Preenche até passar MAX_ENTRIES com janelas já expiradas → o próximo
        // tryAcquire dispara a limpeza (removeIf) e não retém memória.
        for (int i = 0; i < 10_001; i++) {
            store.tryAcquire("rota|ip-" + i, 1, 1);
        }
        clock.advance(1_100);
        // Novo acesso força a limpeza (janelas antigas expiradas removidas).
        assertThat(store.tryAcquire("rota|ip-novo", 1, 1)).isTrue();
    }

    @Test
    void cleanupDoesNotRunWhenMapIsSmall() {
        // L35 negate (<=): com poucas chaves a limpeza NÃO roda — janela antiga
        // expirada permanece no mapa (limpeza é best-effort, só quando cresce).
        assertThat(store.tryAcquire("rota|ip-a", 1, 1)).isTrue();
        clock.advance(1_100);
        assertThat(store.tryAcquire("rota|ip-b", 1, 1)).isTrue();
        // Mutante (size <= MAX sempre) removeria a janela expirada de "a".
        assertThat(store.window("rota|ip-a")).isNotNull();
    }

    @Test
    void cleanupDoesNotRunWhenSizeEqualsMaxEntries() {
        // L35 boundary (>=): exatamente MAX_ENTRIES (10_000) → NÃO dispara a
        // limpeza (só quando size > MAX). Mutante (>=) removeria as expiradas.
        for (int i = 0; i < 10_000; i++) {
            store.tryAcquire("rota|ip-" + i, 1, 1);
        }
        clock.advance(1_100);
        // Este acesso vê size == 10_000 (não > 10_000) → sem limpeza.
        assertThat(store.tryAcquire("rota|ip-trigger", 1, 1)).isTrue();
        assertThat(store.window("rota|ip-0")).isNotNull();
    }

    @Test
    void cleanupKeepsRecentWindowsWhenMapGrows() {
        // L36 Math (now+start) / L36 BooleanTrueReturn: a limpeza remove apenas
        // janelas EXPIRADAS — janelas recentes (elapsed < windowMs) permanecem.
        for (int i = 0; i < 10_001; i++) {
            store.tryAcquire("rota|ip-" + i, 1, 3_600);
        }
        // Dispara a limpeza; todas as janelas são recentes (1h de janela).
        assertThat(store.tryAcquire("rota|ip-trigger", 1, 3_600)).isTrue();
        assertThat(store.window("rota|ip-0")).isNotNull();
    }

    @Test
    void cleanupRemovesExpiredWindowsWhenMapGrows() {
        // L36 negate (<): a limpeza remove janelas com elapsed >= windowMs —
        // mutante (elapsed < windowMs) manteria as expiradas no mapa.
        for (int i = 0; i < 10_001; i++) {
            store.tryAcquire("rota|ip-" + i, 1, 1);
        }
        clock.advance(1_100);
        store.tryAcquire("rota|ip-trigger", 1, 1);
        // Janela expirada DEVE ter sido removida pela limpeza.
        assertThat(store.window("rota|ip-0")).isNull();
    }

    @Test
    void cleanupRemovesWindowExactlyAtBoundary() {
        // L36 ConditionalsBoundary (>= → >): janela com elapsed EXATAMENTE ==
        // windowMs é expirada (>=). Mutante (>) a manteria no mapa.
        for (int i = 0; i < 10_001; i++) {
            store.tryAcquire("rota|ip-" + i, 1, 1);
        }
        // Avança EXATAMENTE 1s (elapsed == windowMs == 1000ms).
        clock.advance(1_000);
        store.tryAcquire("rota|ip-trigger", 1, 1);
        // Janela no limite exato DEVE ser removida pela limpeza.
        assertThat(store.window("rota|ip-0")).isNull();
    }

    @Test
    void windowResetsExactlyAtBoundary() {
        // L40 ConditionalsBoundary (>= → >): janela com elapsed EXATAMENTE ==
        // windowMs reinicia o contador (>=). Mutante (>) contaria na janela
        // antiga (bloqueando a requisição no limite).
        assertThat(store.tryAcquire("rota|ip", 1, 1)).isTrue();
        // Avança EXATAMENTE 1s (elapsed == windowMs == 1000ms).
        clock.advance(1_000);
        // No limite exato a janela reinicia → nova requisição permitida.
        assertThat(store.tryAcquire("rota|ip", 1, 1)).isTrue();
    }
}
