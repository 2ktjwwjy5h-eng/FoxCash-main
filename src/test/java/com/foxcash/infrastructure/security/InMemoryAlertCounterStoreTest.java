package com.foxcash.infrastructure.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do {@link InMemoryAlertCounterStore} (Spec 66 — Fase B): janela fixa
 * por chave, disparo único no limiar (reinicia a janela) e isolamento por chave.
 * A expiração natural da janela é coberta pelo script Lua do Redis (EXPIRE) e
 * pela lógica de reset — aqui o foco é o contrato determinístico da porta.
 */
class InMemoryAlertCounterStoreTest {

    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final InMemoryAlertCounterStore store = new InMemoryAlertCounterStore(CLOCK);

    @Test
    void firesOnceWhenThresholdReachedWithinWindow() {
        // Limiar 5 em 60s: 4 contagens não disparam; a 5ª dispara; a 6ª (janela
        // reiniciada no disparo) não dispara de novo — disparo único.
        for (int i = 0; i < 4; i++) {
            assertThat(store.incrementAndFire("login:master", 5, 60)).isZero();
        }
        assertThat(store.incrementAndFire("login:master", 5, 60)).isEqualTo(1);
        assertThat(store.incrementAndFire("login:master", 5, 60)).isZero();
    }

    @Test
    void belowThresholdNeverFires() {
        for (int i = 0; i < 9; i++) {
            assertThat(store.incrementAndFire("signature:/webhooks/pix", 10, 60)).isZero();
        }
    }

    @Test
    void countersAreIsolatedByKey() {
        assertThat(store.incrementAndFire("login:master", 2, 60)).isZero();
        assertThat(store.incrementAndFire("login:merchant", 2, 60)).isZero();
        assertThat(store.incrementAndFire("login:merchant", 2, 60)).isEqualTo(1);
        // master continua com 1 (abaixo do limiar) — chaves independentes.
        assertThat(store.incrementAndFire("login:master", 2, 60)).isEqualTo(1);
    }
}
