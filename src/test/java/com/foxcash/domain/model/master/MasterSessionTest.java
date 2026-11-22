package com.foxcash.domain.model.master;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do step-up na sessão do master (Spec 69, F3 — T3.2): {@code
 * markSteppedUp} grava {@code stepped_up_at}; {@code isSteppedUp} exige sessão
 * válida E step-up dentro da janela (default 15 min); janela vencida/sessão
 * revogada/ausência de step-up → false.
 */
class MasterSessionTest {

    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private MasterSession activeSession() {
        return MasterSession.restore(UUID.randomUUID(), UUID.randomUUID(), "hash",
                NOW.minusSeconds(60), NOW.plusSeconds(3600), null, null);
    }

    @Test
    void issuedSessionHasNoStepUp() {
        MasterSession session = MasterSession.issue(UUID.randomUUID(), "hash", NOW, NOW.plusSeconds(3600));
        assertThat(session.steppedUpAt()).isNull();
        assertThat(session.isSteppedUp(NOW, WINDOW)).isFalse();
    }

    @Test
    void markSteppedUpEnablesWindow() {
        MasterSession session = activeSession();
        session.markSteppedUp(NOW);
        assertThat(session.isSteppedUp(NOW, WINDOW)).isTrue();
        // Dentro da janela (15 min depois) → ainda elevada.
        assertThat(session.isSteppedUp(NOW.plus(Duration.ofMinutes(14)), WINDOW)).isTrue();
    }

    @Test
    void expiredWindowIsNoLongerSteppedUp() {
        MasterSession session = activeSession();
        session.markSteppedUp(NOW.minus(Duration.ofMinutes(16)));
        assertThat(session.isSteppedUp(NOW, WINDOW)).isFalse();
    }

    @Test
    void revokedSessionIsNotSteppedUpEvenWithinWindow() {
        MasterSession session = activeSession();
        session.markSteppedUp(NOW);
        session.revoke(NOW.plusSeconds(10));
        assertThat(session.isSteppedUp(NOW.plusSeconds(20), WINDOW)).isFalse();
    }

    @Test
    void restoreRoundTripsSteppedUpAt() {
        MasterSession restored = MasterSession.restore(UUID.randomUUID(), UUID.randomUUID(), "hash",
                NOW.minusSeconds(60), NOW.plusSeconds(3600), null, NOW);
        assertThat(restored.steppedUpAt()).isEqualTo(NOW);
        assertThat(restored.isSteppedUp(NOW, WINDOW)).isTrue();
    }
}
