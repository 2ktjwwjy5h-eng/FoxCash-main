package com.foxcash.domain.model.master;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do {@link MasterRecoveryCode} (Spec 44): código de recuperação do MFA
 * do master, SHA-256 single-use — `use()` marca o timestamp e `isUsed()` nunca
 * pode devolver um valor errado (oráculo de lockout).
 */
class MasterRecoveryCodeTest {

    private static final UUID ID = UUID.randomUUID();
    private static final UUID METHOD_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-01T09:00:00Z");

    @Test
    void createBuildsUnusedCode() {
        MasterRecoveryCode code = MasterRecoveryCode.create(ID, METHOD_ID, "hash-abc", NOW);

        assertThat(code.id()).isEqualTo(ID);
        assertThat(code.methodId()).isEqualTo(METHOD_ID);
        assertThat(code.codeHash()).isEqualTo("hash-abc");
        assertThat(code.createdAt()).isEqualTo(NOW);
        assertThat(code.usedAt()).isNull();
        assertThat(code.isUsed()).isFalse();
    }

    @Test
    void useMarksCodeAsUsedKeepingOriginalImmutable() {
        MasterRecoveryCode code = MasterRecoveryCode.create(ID, METHOD_ID, "hash-abc", NOW);

        MasterRecoveryCode used = code.use(NOW.plusSeconds(30));

        assertThat(used.usedAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(used.isUsed()).isTrue();
        assertThat(used.id()).isEqualTo(ID);
        assertThat(used.createdAt()).isEqualTo(NOW);
        // Imutável: a instância original continua não-usada.
        assertThat(code.isUsed()).isFalse();
    }

    @Test
    void restorePreservesUsedStateAndTimestamps() {
        Instant usedAt = NOW.plusSeconds(300);
        MasterRecoveryCode code = MasterRecoveryCode.restore(ID, METHOD_ID, "hash-abc", usedAt, NOW);

        assertThat(code.id()).isEqualTo(ID);
        assertThat(code.methodId()).isEqualTo(METHOD_ID);
        assertThat(code.codeHash()).isEqualTo("hash-abc");
        assertThat(code.usedAt()).isEqualTo(usedAt);
        assertThat(code.createdAt()).isEqualTo(NOW);
        assertThat(code.isUsed()).isTrue();
    }
}
