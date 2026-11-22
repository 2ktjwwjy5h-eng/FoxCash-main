package com.foxcash.domain.model.merchant;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do detalhe TOTP do lojista no portal (Spec 94): contagem de falhas
 * com lockout automatico (expira sozinho) e reset no codigo valido
 * (anti-replay). Espelho do {@code MasterTotpTest} (Spec 44).
 */
class MerchantTotpTest {

    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");

    private static MerchantTotp totp() {
        return MerchantTotp.create(UUID.randomUUID(), "v1:secret", NOW);
    }

    @Test
    void recordFailedAttemptIncrements() {
        MerchantTotp one = totp().recordFailedAttempt(5, 15, NOW);

        // Mutation Math: failedAttempts + 1 <-> failedAttempts - 1.
        assertThat(one.failedAttempts()).isEqualTo(1);
        assertThat(one.isLocked(NOW)).isFalse();
    }

    @Test
    void recordFailedAttemptLocksAtMaxAndExpires() {
        MerchantTotp totp = totp();
        for (int i = 0; i < 5; i++) {
            totp = totp.recordFailedAttempt(5, 15, NOW);
        }

        assertThat(totp.failedAttempts()).isEqualTo(5);
        assertThat(totp.lockedUntil()).isEqualTo(NOW.plusSeconds(15 * 60));
        assertThat(totp.isLocked(NOW)).isTrue();
        // No limite exato (now == lockedUntil) ainda está lockado; expira DEPOIS.
        assertThat(totp.isLocked(NOW.plusSeconds(15 * 60))).isTrue();
        assertThat(totp.isLocked(NOW.plusSeconds(15 * 60).plusSeconds(1))).isFalse();
    }

    @Test
    void recordValidCodeResetsFailuresAndStoresHash() {
        MerchantTotp totp = totp().recordFailedAttempt(5, 15, NOW).recordFailedAttempt(5, 15, NOW);

        MerchantTotp reset = totp.recordValidCode("abc123hash", NOW);

        assertThat(reset.failedAttempts()).isZero();
        assertThat(reset.lastCodeHash()).isEqualTo("abc123hash");
        assertThat(reset.lockedUntil()).isNull();
        assertThat(reset.isLocked(NOW)).isFalse();
    }
}
