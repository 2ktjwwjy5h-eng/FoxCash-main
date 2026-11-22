package com.foxcash.application.service;

import com.foxcash.domain.exception.BroadcastNotFoundException;
import com.foxcash.domain.exception.StoreBotNotFoundException;
import com.foxcash.domain.model.broadcast.Broadcast;
import com.foxcash.domain.model.broadcast.BroadcastLog;
import com.foxcash.domain.model.broadcast.BroadcastLogStatus;
import com.foxcash.domain.model.broadcast.BroadcastStatus;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.domain.port.in.BroadcastDispatchUseCase;
import com.foxcash.domain.port.out.BroadcastLogRepository;
import com.foxcash.domain.port.out.BroadcastRepository;
import com.foxcash.domain.port.out.BroadcastWelcomeSentRepository;
import com.foxcash.domain.port.out.StoreBotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Aplicação: dispatch dos broadcasts pelo bot (Spec 79) — API key do lojista
 * (principal = {@code merchantId}), padrão Spec 51. O bot busca as pendências
 * ({@code due} → marca {@code PROCESSING}), envia com rate limit nativo e
 * reporta o resultado por destinatário ({@code reportLog}, idempotente — a
 * UNIQUE {@code (broadcast_id, recipient_id)} garante que replay não duplica e
 * falha de um destinatário não derruba os demais); o backend conclui via
 * {@code markSent} ({@code COMPLETED} se ≥1 SUCCESS, senão {@code FAILED}).
 *
 * <p><b>WELCOME</b> ({@link #welcomeTrigger}): evento de entrada de membro
 * (chat_member/guildMemberAdd) — devolve a mensagem de boas-vindas ativa do
 * bot para o chat (mais recente {@code PENDING} com {@code target_chat_id});
 * idempotente por {@code (chatId, userId)} (tabela {@code broadcast_welcome_sent}).
 */
@Service
public class BroadcastDispatchService implements BroadcastDispatchUseCase {

    private final BroadcastRepository broadcastRepository;
    private final BroadcastLogRepository logRepository;
    private final BroadcastWelcomeSentRepository welcomeSentRepository;
    private final StoreBotRepository botRepository;
    private final Clock clock;

    public BroadcastDispatchService(BroadcastRepository broadcastRepository,
                                    BroadcastLogRepository logRepository,
                                    BroadcastWelcomeSentRepository welcomeSentRepository,
                                    StoreBotRepository botRepository,
                                    Clock clock) {
        this.broadcastRepository = broadcastRepository;
        this.logRepository = logRepository;
        this.welcomeSentRepository = welcomeSentRepository;
        this.botRepository = botRepository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public List<Broadcast> due(UUID merchantId, Instant dueBefore) {
        List<Broadcast> due = broadcastRepository.findDue(merchantId, dueBefore);
        Instant now = clock.instant();
        for (Broadcast broadcast : due) {
            // O bot pegou a pendência — PROCESSING (replay/concorrência no-op).
            if (broadcast.markProcessing(now)) {
                broadcastRepository.save(broadcast);
            }
        }
        return due;
    }

    @Override
    @Transactional
    public void markSent(UUID merchantId, UUID broadcastId) {
        Broadcast broadcast = requireBroadcast(merchantId, broadcastId);
        if (broadcast.status() != BroadcastStatus.PROCESSING) {
            return; // idempotente — replay de um envio já concluído é no-op (204)
        }
        boolean anySuccess = logRepository.countByBroadcastIdAndStatus(
                broadcastId, BroadcastLogStatus.SUCCESS) > 0;
        if (anySuccess) {
            broadcast.complete(clock.instant());
        } else {
            broadcast.fail("nenhum destinatario recebeu a mensagem (SUCCESS)", clock.instant());
        }
        broadcastRepository.save(broadcast);
    }

    /**
     * Re-agenda os REPOST concluídos (job {@code foxcash-broadcast-repost}, D10):
     * {@code COMPLETED → PENDING} com {@code scheduled_at = now + interval}. O
     * broadcast REPOST permanece ativo até ser cancelado (DELETE de PENDING).
     * Sem escopo de lojista (job de sistema).
     */
    @Transactional
    public void rescheduleCompletedReposts() {
        Instant now = clock.instant();
        for (Broadcast repost : broadcastRepository.findCompletedReposts()) {
            if (repost.reschedule(now)) {
                broadcastRepository.save(repost);
            }
        }
    }

    @Override
    @Transactional
    public void reportLog(UUID merchantId, UUID broadcastId, String recipientId,
                          BroadcastLogStatus status, String errorMessage) {
        // Escopo por lojista antes de gravar (404 cross-lojista — IDOR-safe).
        requireBroadcast(merchantId, broadcastId);
        // Idempotência: replay do MESMO (broadcast, recipient) não duplica.
        if (logRepository.findByBroadcastIdAndRecipientId(broadcastId, recipientId).isPresent()) {
            return;
        }
        logRepository.save(BroadcastLog.create(broadcastId, recipientId, status,
                errorMessage, clock.instant()));
    }

    @Override
    @Transactional
    public WelcomeResult welcomeTrigger(UUID merchantId, UUID botId, String chatId, String userId) {
        // Idempotência por (chatId, userId): evento duplicado (Telegram re-envia
        // chat_member) não dispara 2×.
        if (welcomeSentRepository.existsByChatIdAndUserId(chatId, userId)) {
            return null;
        }
        StoreBot bot = botRepository.findByIdAndMerchantId(botId, merchantId)
                .orElseThrow(StoreBotNotFoundException::new);
        Broadcast welcome = broadcastRepository.findWelcomeForChat(bot.id(), chatId).stream()
                .findFirst().orElse(null);
        if (welcome == null) {
            return null; // sem mensagem ativa → no-op (204)
        }
        // Marca como atendido ANTES de devolver — raça com a UNIQUE é tratada no
        // adapter (quem perde o insert vira no-op).
        welcomeSentRepository.record(welcome.id(), chatId, userId);
        return new WelcomeResult(
                welcome.id(), welcome.content(), welcome.mediaUrl(),
                welcome.mediaType() == null ? null : welcome.mediaType().name(),
                welcome.mediaFileId(),
                welcome.buttons() == null ? null : welcome.buttons().json());
    }

    private Broadcast requireBroadcast(UUID merchantId, UUID broadcastId) {
        return broadcastRepository.findByIdAndMerchantId(broadcastId, merchantId)
                .orElseThrow(BroadcastNotFoundException::new);
    }
}
