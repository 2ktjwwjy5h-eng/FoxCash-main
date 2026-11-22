package com.foxcash.application.service;

import com.foxcash.domain.exception.BroadcastNotFoundException;
import com.foxcash.domain.exception.StoreBotNotFoundException;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.broadcast.Broadcast;
import com.foxcash.domain.model.broadcast.BroadcastButtons;
import com.foxcash.domain.model.broadcast.BroadcastMediaType;
import com.foxcash.domain.model.broadcast.BroadcastMode;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.domain.port.in.BroadcastManagementUseCase;
import com.foxcash.domain.port.out.BroadcastRepository;
import com.foxcash.domain.port.out.StoreBotRepository;
import com.foxcash.infrastructure.security.WebhookUrlValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Aplicação: criação e cancelamento de broadcasts pelo portal (Spec 79) — tudo
 * por {@code merchantId} do principal (404 {@code broadcast_not_found}/
 * {@code bot_not_found} cross-lojista). O disparo nunca é síncrono: criar
 * persiste ({@code PENDING}) e o bot (Spec 52) busca via API; cancelar faz
 * DELETE físico de {@code PENDING} (D3) — {@code PROCESSING} → 409.
 *
 * <p><b>Validação por plataforma</b>: o {@code buttons_json} é validado pelo
 * value object {@link BroadcastButtons} com a URL SSRF-safe (a aplicação injeta
 * {@code WebhookUrlValidator.validate(url, false)} — Spec 35) e os limites da
 * plataforma do bot (D8). Auditoria Spec 41 na mesma transação
 * ({@code BROADCAST_CREATED}/{@code BROADCAST_SCHEDULED}/{@code BROADCAST_CANCELLED}).
 */
@Service
public class BroadcastCommandService implements BroadcastManagementUseCase {

    private final BroadcastRepository broadcastRepository;
    private final StoreBotRepository botRepository;
    private final PlatformActivityRecorder activityRecorder;
    private final Clock clock;

    public BroadcastCommandService(BroadcastRepository broadcastRepository,
                                   StoreBotRepository botRepository,
                                   PlatformActivityRecorder activityRecorder,
                                   Clock clock) {
        this.broadcastRepository = broadcastRepository;
        this.botRepository = botRepository;
        this.activityRecorder = activityRecorder;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Broadcast create(UUID merchantId, CreateBroadcastCommand command) {
        Instant now = clock.instant();
        StoreBot bot = requireBot(merchantId, command.storeBotId());
        BroadcastButtons buttons = parseButtons(command, bot);
        Broadcast broadcast = Broadcast.create(
                command.storeBotId(),
                requireMode(command.mode()),
                command.targetChatId(),
                command.content(),
                command.mediaUrl(),
                parseMediaType(command.mediaType()),
                command.mediaFileId(),
                buttons,
                command.scheduledAt(),
                command.intervalSeconds(),
                now);
        Broadcast saved = broadcastRepository.save(broadcast);
        activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                saved.mode() == BroadcastMode.SCHEDULED
                        ? PlatformAction.BROADCAST_SCHEDULED : PlatformAction.BROADCAST_CREATED,
                "BROADCAST", saved.id(), merchantId, details(saved));
        return saved;
    }

    @Override
    @Transactional
    public void cancel(UUID merchantId, UUID broadcastId) {
        Broadcast broadcast = requireBroadcast(merchantId, broadcastId);
        if (!broadcast.isCancellable()) {
            throw new IllegalStateException(
                    "Broadcast em estado " + broadcast.status() + " nao pode ser cancelado (somente PENDING)");
        }
        broadcastRepository.delete(broadcast);
        activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                PlatformAction.BROADCAST_CANCELLED, "BROADCAST", broadcastId, merchantId,
                Map.of("mode", broadcast.mode(), "status", broadcast.status()));
    }

    private Broadcast requireBroadcast(UUID merchantId, UUID broadcastId) {
        return broadcastRepository.findByIdAndMerchantId(broadcastId, merchantId)
                .orElseThrow(BroadcastNotFoundException::new);
    }

    private StoreBot requireBot(UUID merchantId, UUID botId) {
        if (botId == null) {
            throw new IllegalArgumentException("store_bot_id e obrigatorio no broadcast");
        }
        return botRepository.findByIdAndMerchantId(botId, merchantId)
                .orElseThrow(StoreBotNotFoundException::new);
    }

    /** Valida o buttons_json conforme a plataforma do bot (D8) — URL SSRF-safe. */
    private static BroadcastButtons parseButtons(CreateBroadcastCommand command, StoreBot bot) {
        if (command.buttonsJson() == null || command.buttonsJson().isBlank()) {
            return null;
        }
        // WebhookUrlValidator.validate(url, false) — SSRF-safe (Spec 35): rejeita
        // endereços internos/loopback. A plataforma define os limites (D8).
        return BroadcastButtons.parse(command.buttonsJson(), bot.platform(),
                url -> WebhookUrlValidator.validate(url, false));
    }

    private static BroadcastMode requireMode(BroadcastMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("mode e obrigatorio no broadcast");
        }
        return mode;
    }

    private static BroadcastMediaType parseMediaType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return BroadcastMediaType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("media_type invalido: " + raw
                    + " (use image, video ou audio)");
        }
    }

    private static Map<String, Object> details(Broadcast broadcast) {
        return Map.of(
                "mode", broadcast.mode(),
                "status", broadcast.status(),
                "targetChatId", broadcast.targetChatId() == null ? "" : broadcast.targetChatId(),
                "scheduledAt", broadcast.scheduledAt() == null ? "" : broadcast.scheduledAt().toString());
    }
}
