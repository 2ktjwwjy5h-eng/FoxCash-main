package com.foxcash.application.service;

import com.foxcash.domain.model.botfunnel.BotLead;
import com.foxcash.domain.model.botfunnel.UtmParams;
import com.foxcash.domain.port.out.BotLeadRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Aplicação: registro de leads por bot (Spec 75) — usuário único que inicia
 * conversa com o bot. Chamado pelo endpoint público do Telegram (Spec 74/D5.3)
 * e pela interação Discord (Spec 74/D6). <b>Idempotente</b>: UNIQUE
 * {@code (bot_id, platform_user_id)} + no-op (replay de update/interação não
 * duplica).
 *
 * <p>Spec 81/D4: na CRIAÇÃO do lead, persiste as UTMs do link de entrada
 * ("primeiro valor vence") e dispara o tracking de {@code lead} na mesma
 * transação.
 */
@Service
public class BotLeadService {

    private final BotLeadRepository leadRepository;
    private final TrackingDispatchService trackingDispatch;
    private final Clock clock;

    public BotLeadService(BotLeadRepository leadRepository,
                          TrackingDispatchService trackingDispatch,
                          Clock clock) {
        this.leadRepository = leadRepository;
        this.trackingDispatch = trackingDispatch;
        this.clock = clock;
    }

    /** Registra (ou mantém) o lead — sem UTMs (variante usada por testes/legado). */
    @Transactional
    public void recordLead(UUID botId, String platformUserId) {
        recordLead(botId, platformUserId, null);
    }

    /**
     * Registra (ou mantém) o lead de um usuário num bot. Idempotente: o
     * primeiro registro cria; replay (update duplicado do Telegram/interação
     * repetida) é no-op — nunca duplica nem sobrescreve as UTMs.
     */
    @Transactional
    public void recordLead(UUID botId, String platformUserId, UtmParams utm) {
        if (botId == null || platformUserId == null || platformUserId.isBlank()) {
            return; // update sem usuário (ex.: channel_post) — não é lead
        }
        if (leadRepository.findByBotIdAndPlatformUserId(botId, platformUserId).isPresent()) {
            return; // replay — lead já registrado (UTMs não mudam)
        }
        boolean created = leadRepository
                .saveIfAbsent(BotLead.create(botId, platformUserId, utm, clock.instant()))
                .isPresent();
        if (created) {
            // Spec 81: novo lead → disparo de tracking (mesma transação).
            trackingDispatch.onLead(botId, platformUserId, utm);
        }
    }
}
