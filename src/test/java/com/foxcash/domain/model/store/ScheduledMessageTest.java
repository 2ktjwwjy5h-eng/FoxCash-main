package com.foxcash.domain.model.store;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes da mensagem programada da loja (Spec 51): criação (XOR
 * scheduledAt/cron; cron 6 campos validado; text 1–1000), marcação SENT
 * (idempotente) e elegibilidade (isDue — one-shot por data, cron pelo próximo
 * disparo).
 */
class ScheduledMessageTest {

    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");
    private static final UUID STORE_ID = UUID.randomUUID();

    @Test
    void createOneShotMessage() {
        Instant scheduledAt = NOW.plusSeconds(3600);

        ScheduledMessage message = ScheduledMessage.create(STORE_ID, "Promoção do dia! 🎉",
                scheduledAt, null, ScheduledMessageAudience.BUYERS, NOW);

        assertThat(message.scheduledAt()).isEqualTo(scheduledAt);
        assertThat(message.cron()).isNull();
        assertThat(message.audience()).isEqualTo(ScheduledMessageAudience.BUYERS);
        assertThat(message.status()).isEqualTo(ScheduledMessageStatus.SCHEDULED);
        assertThat(message.sentAt()).isNull();
    }

    @Test
    void createDefaultsAudienceToBuyers() {
        ScheduledMessage message = ScheduledMessage.create(STORE_ID, "Promoção!",
                NOW.plusSeconds(60), null, null, NOW);

        assertThat(message.audience()).isEqualTo(ScheduledMessageAudience.BUYERS);
    }

    @Test
    void createRecurringMessageValidatesCron() {
        ScheduledMessage message = ScheduledMessage.create(STORE_ID, "Lembrete semanal.",
                null, "0 0 10 * * MON", null, NOW);

        assertThat(message.cron()).isEqualTo("0 0 10 * * MON");
        assertThat(message.scheduledAt()).isNull();
    }

    @Test
    void createRejectsBothOrNeitherSchedule() {
        assertThatThrownBy(() -> ScheduledMessage.create(STORE_ID, "X",
                NOW.plusSeconds(60), "0 0 10 * * MON", null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exatamente um");

        assertThatThrownBy(() -> ScheduledMessage.create(STORE_ID, "X",
                null, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exatamente um");
    }

    @Test
    void createRejectsInvalidCron() {
        assertThatThrownBy(() -> ScheduledMessage.create(STORE_ID, "X",
                null, "99 99 99 99 99 99", null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cron invalida");
    }

    @Test
    void createRejectsBlankOrTooLongText() {
        assertThatThrownBy(() -> ScheduledMessage.create(STORE_ID, "  ", NOW.plusSeconds(60),
                null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("text e obrigatorio");

        assertThatThrownBy(() -> ScheduledMessage.create(STORE_ID, "x".repeat(1001),
                NOW.plusSeconds(60), null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no maximo 1000");
    }

    @Test
    void createAcceptsMaxTextLength() {
        // Mutation (ConditionalsBoundary > <-> >=): text de 1000 chars é aceito.
        ScheduledMessage message = ScheduledMessage.create(STORE_ID, "x".repeat(1000),
                NOW.plusSeconds(60), null, null, NOW);

        assertThat(message.text()).hasSize(1000);
    }

    @Test
    void markSentTransitionsOnlyOnce() {
        ScheduledMessage message = ScheduledMessage.create(STORE_ID, "Promo!",
                NOW.plusSeconds(60), null, null, NOW);

        assertThat(message.markSent(NOW.plusSeconds(120))).isTrue();
        assertThat(message.status()).isEqualTo(ScheduledMessageStatus.SENT);
        assertThat(message.sentAt()).isEqualTo(NOW.plusSeconds(120));

        // Replay → no-op idempotente (204 no controller).
        assertThat(message.markSent(NOW.plusSeconds(180))).isFalse();
        assertThat(message.sentAt()).isEqualTo(NOW.plusSeconds(120));
    }

    @Test
    void isDueForOneShotBeforeScheduledAt() {
        ScheduledMessage message = ScheduledMessage.create(STORE_ID, "Promo!",
                NOW.plusSeconds(3600), null, null, NOW);

        assertThat(message.isDue(NOW.plusSeconds(1800))).isFalse();
        assertThat(message.isDue(NOW.plusSeconds(3600))).isTrue();
        assertThat(message.isDue(NOW.plusSeconds(7200))).isTrue();
    }

    @Test
    void isDueForCronByNextFire() {
        // Cron diário às 10:00 UTC; criada às 12:00 — o próximo disparo é amanhã 10:00.
        ScheduledMessage message = ScheduledMessage.create(STORE_ID, "Diária.",
                null, "0 0 10 * * *", null, NOW);

        Instant tomorrow10 = Instant.parse("2026-08-07T10:00:00Z");
        assertThat(message.isDue(tomorrow10.minusSeconds(1))).isFalse();
        assertThat(message.isDue(tomorrow10)).isTrue();
        assertThat(message.isDue(tomorrow10.plusSeconds(60))).isTrue();
    }

    @Test
    void isDueFalseWhenAlreadySent() {
        ScheduledMessage message = ScheduledMessage.create(STORE_ID, "Promo!",
                NOW.minusSeconds(60), null, null, NOW);
        message.markSent(NOW);

        assertThat(message.isDue(NOW.plusSeconds(60))).isFalse();
    }

    @Test
    void restoreLoadsPersistedState() {
        UUID id = UUID.randomUUID();
        Instant createdAt = NOW.minusSeconds(3600);
        Instant sentAt = NOW.minusSeconds(60);

        ScheduledMessage message = ScheduledMessage.restore(id, STORE_ID, "Promo!",
                null, "0 0 10 * * MON", ScheduledMessageAudience.BUYERS,
                ScheduledMessageStatus.SENT, sentAt, createdAt);

        assertThat(message.id()).isEqualTo(id);
        assertThat(message.status()).isEqualTo(ScheduledMessageStatus.SENT);
        assertThat(message.sentAt()).isEqualTo(sentAt);
        assertThat(message.createdAt()).isEqualTo(createdAt);
    }

    @Test
    void exposesStoreId() {
        // Mutation (NullReturnVals L138): storeId() devolve a loja (mutante → null).
        ScheduledMessage message = ScheduledMessage.create(STORE_ID, "Promo!",
                NOW.plusSeconds(60), null, null, NOW);

        assertThat(message.storeId()).isEqualTo(STORE_ID);
    }

    @Test
    void createAcceptsCronExactlyAtMaxLength() {
        // Mutation (ConditionalsBoundary L122: cron.length() > MAX_CRON → >=): cron
        // de EXATAMENTE 100 chars é aceito (o mutante responderia "no maximo 100").
        // Cron válido de 6 campos com lista longa de segundos (0-31) e dow "?".
        String cron = "0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,"
                + "24,25,26,27,28,29,30,31 0,1 0 1-31 * ?";
        assertThat(cron).hasSize(100);

        ScheduledMessage message = ScheduledMessage.create(STORE_ID, "X",
                null, cron, null, NOW);

        assertThat(message.cron()).isEqualTo(cron);
    }

    @Test
    void createRejectsCronAboveMaxLength() {
        // 101 chars → rejeitado (guarda de tamanho, ANTES do parse).
        assertThatThrownBy(() -> ScheduledMessage.create(STORE_ID, "X",
                null, "x".repeat(101), null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no maximo 100");
    }
}
