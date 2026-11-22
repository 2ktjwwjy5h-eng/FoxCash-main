package com.foxcash.domain.model.broadcast;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes do log por destinatário do broadcast (Spec 79): criação com
 * normalização do {@code error_message} (só em BLOCKED/FAILED, truncado a 500)
 * e restauração.
 */
class BroadcastLogTest {

    private static final UUID BROADCAST_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");

    @Test
    void createSuccessIgnoresErrorMessage() {
        BroadcastLog log = BroadcastLog.create(BROADCAST_ID, "12345",
                BroadcastLogStatus.SUCCESS, "ignorado", NOW);

        assertThat(log.broadcastId()).isEqualTo(BROADCAST_ID);
        assertThat(log.recipientId()).isEqualTo("12345");
        assertThat(log.status()).isEqualTo(BroadcastLogStatus.SUCCESS);
        assertThat(log.errorMessage()).isNull();
        assertThat(log.sentAt()).isEqualTo(NOW);
    }

    @Test
    void createFailureKeepsErrorMessageTruncated() {
        String longError = "e".repeat(600);
        BroadcastLog log = BroadcastLog.create(BROADCAST_ID, "12345",
                BroadcastLogStatus.FAILED, longError, NOW);

        assertThat(log.errorMessage()).hasSize(500);
    }

    @Test
    void createRequiresRecipientId() {
        assertThatThrownBy(() -> BroadcastLog.create(BROADCAST_ID, " ",
                BroadcastLogStatus.BLOCKED, "x", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recipient_id");

        assertThatThrownBy(() -> BroadcastLog.create(BROADCAST_ID, "12345",
                null, "x", NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void restoreRehydratesWithoutValidation() {
        BroadcastLog restored = BroadcastLog.restore(BROADCAST_ID, "12345",
                BroadcastLogStatus.SUCCESS, null, NOW);

        assertThat(restored.recipientId()).isEqualTo("12345");
        assertThat(restored.status()).isEqualTo(BroadcastLogStatus.SUCCESS);
    }
}
