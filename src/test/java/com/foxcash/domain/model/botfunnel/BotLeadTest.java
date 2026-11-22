package com.foxcash.domain.model.botfunnel;

import com.foxcash.domain.model.crm.LeadHandoffStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do {@link BotLead} (Spec 75) + transições de handoff (Spec 90) —
 * {@code pauseBot}/{@code resumeBot} idempotentes (repetir o mesmo estado é
 * no-op, sem erro).
 */
class BotLeadTest {

    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");
    private static final UUID BOT_ID = UUID.randomUUID();

    @Test
    void createDefaultsToNovoAndBot() {
        BotLead lead = BotLead.create(BOT_ID, "u-1", NOW);

        assertThat(lead.handoffStatus()).isEqualTo(LeadHandoffStatus.BOT);
        assertThat(lead.id()).isNotNull();
    }

    @Test
    void pauseBotMovesToHuman() {
        BotLead lead = BotLead.create(BOT_ID, "u-1", NOW);

        BotLead paused = lead.pauseBot();

        assertThat(paused.handoffStatus()).isEqualTo(LeadHandoffStatus.HUMAN);
        assertThat(paused.id()).isEqualTo(lead.id()); // mesma linha persistida
    }

    @Test
    void pauseBotIsIdempotent() {
        BotLead paused = BotLead.create(BOT_ID, "u-1", NOW).pauseBot();

        // Repetir HUMAN é no-op (retorna o MESMO objeto — sem nova versão).
        assertThat(paused.pauseBot()).isSameAs(paused);
    }

    @Test
    void resumeBotMovesBackToBot() {
        BotLead paused = BotLead.create(BOT_ID, "u-1", NOW).pauseBot();

        BotLead resumed = paused.resumeBot();

        assertThat(resumed.handoffStatus()).isEqualTo(LeadHandoffStatus.BOT);
        assertThat(resumed.id()).isEqualTo(paused.id());
    }

    @Test
    void resumeBotIsIdempotent() {
        BotLead lead = BotLead.create(BOT_ID, "u-1", NOW);

        assertThat(lead.resumeBot()).isSameAs(lead);
    }
}
