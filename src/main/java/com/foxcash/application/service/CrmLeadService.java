package com.foxcash.application.service;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.botfunnel.BotLead;
import com.foxcash.domain.model.crm.ChatMessage;
import com.foxcash.domain.model.crm.CrmLeadStatus;
import com.foxcash.domain.model.storebot.BotPlatform;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.domain.exception.LeadBlockedException;
import com.foxcash.domain.exception.LeadNotFoundException;
import com.foxcash.domain.exception.MessageSendFailedException;
import com.foxcash.domain.port.out.BotLeadRepository;
import com.foxcash.domain.port.out.ChatMessageRepository;
import com.foxcash.domain.port.out.StoreBotRepository;
import com.foxcash.domain.port.out.TelegramBotApi;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aplicação: CRM do portal (Spec 83) — busca/filtros de leads, resumo (cards),
 * envio de mensagem do painel (REST — reusa a integração da Spec 74) e marcação
 * de lida. Escopo por lojista (404 cross-lojista). Auditoria (Spec 41) na mesma
 * transação: {@code CHAT_MESSAGE_SENT} e {@code LEAD_BLOCKED}.
 */
@Service
public class CrmLeadService {

    /** Resumo dos cards do painel (Spec 83 C4). */
    public record CrmSummary(long novos, long pendentes, long pagos, long bloqueados, long total) {
    }

    private final BotLeadRepository leadRepository;
    private final StoreBotRepository botRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatEventPublisher eventPublisher;
    private final TelegramBotApi telegramApi;
    private final LeadStatusDerivationService statusDerivation;
    private final PlatformActivityRecorder activityRecorder;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public CrmLeadService(BotLeadRepository leadRepository,
                          StoreBotRepository botRepository,
                          ChatMessageRepository messageRepository,
                          ChatEventPublisher eventPublisher,
                          TelegramBotApi telegramApi,
                          LeadStatusDerivationService statusDerivation,
                          PlatformActivityRecorder activityRecorder,
                          MeterRegistry meterRegistry,
                          Clock clock) {
        this.leadRepository = leadRepository;
        this.botRepository = botRepository;
        this.messageRepository = messageRepository;
        this.eventPublisher = eventPublisher;
        this.telegramApi = telegramApi;
        this.statusDerivation = statusDerivation;
        this.activityRecorder = activityRecorder;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /** Lista paginada de leads do lojista (busca {@code q} + filtros — Spec 83 C3).
     *  {@code botIds} (Spec 124): bots do perfil ativo (null → todos do lojista). */
    @Transactional(readOnly = true)
    public PageResult<BotLead> search(UUID merchantId, List<UUID> botIds, String q, UUID botId,
                                      CrmLeadStatus status, Instant from, Instant to,
                                      int page, int size) {
        return leadRepository.search(merchantId, botIds, q, botId, status, from, to, page, size);
    }

    /** Detalhe do lead (404 cross-lojista — Spec 83 C3). */
    @Transactional(readOnly = true)
    public BotLead get(UUID merchantId, UUID leadId) {
        return leadRepository.findByIdAndMerchantId(merchantId, leadId)
                .orElseThrow(() -> new LeadNotFoundException("Lead nao encontrado"));
    }

    /**
     * Enriquecimento do CRM (Spec 119): última interação + nome do plano da
     * última compra (colunas novas da tabela). Empty se o lead não é do lojista.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<BotLeadRepository.LeadEnrichment> enrich(UUID merchantId, UUID leadId) {
        return leadRepository.findEnrichment(merchantId, leadId);
    }

    /** Cards do painel (Spec 83 C4) — contagem por status no período; botIds (Spec 124) opcional. */
    @Transactional(readOnly = true)
    public CrmSummary summary(UUID merchantId, List<UUID> botIds, Instant from, Instant to) {
        Map<CrmLeadStatus, Long> counts = leadRepository.countByStatusForMerchant(merchantId, botIds, from, to);
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        return new CrmSummary(
                counts.getOrDefault(CrmLeadStatus.NOVO, 0L),
                counts.getOrDefault(CrmLeadStatus.PENDENTE, 0L),
                counts.getOrDefault(CrmLeadStatus.PAGO, 0L),
                counts.getOrDefault(CrmLeadStatus.BLOQUEADO, 0L),
                total);
    }

    /**
     * Envia mensagem do painel (Spec 83 D5): valida lead do lojista (404) e
     * status ≠ BLOQUEADO (409 {@code lead_blocked}); envia via Telegram
     * {@code sendMessage(chatId = platform_user_id)}; sucesso → persiste
     * {@code ADMIN} + auditoria {@code CHAT_MESSAGE_SENT}; bloqueio 403 →
     * lead {@code BLOQUEADO} + auditoria {@code LEAD_BLOCKED} + 409; outra
     * falha → 502 sem persistir.
     */
    @Transactional
    public ChatMessage sendMessage(UUID merchantId, UUID leadId, String text, String mediaUrl) {
        if ((text == null || text.isBlank()) && (mediaUrl == null || mediaUrl.isBlank())) {
            throw new IllegalArgumentException("mensagem exige texto ou midia");
        }
        BotLead lead = get(merchantId, leadId);
        if (lead.status() == CrmLeadStatus.BLOQUEADO) {
            throw new LeadBlockedException("Lead bloqueou o bot — envio recusado");
        }
        StoreBot bot = botRepository.findByIdAndMerchantId(lead.botId(), merchantId)
                .orElseThrow(() -> new LeadNotFoundException("Bot do lead nao encontrado"));
        if (bot.platform() != BotPlatform.TELEGRAM) {
            // Decisão de implementação (Spec 83 D5): o envio do painel cobre
            // Telegram (Bot API sendMessage). Bots Discord não têm DM REST no
            // backend (o gateway vive no renderizador) — fora do escopo desta fase.
            throw new MessageSendFailedException(
                    "Envio pelo painel para bots Discord ainda nao e suportado", null);
        }
        try {
            telegramApi.sendMessage(bot.token(), lead.platformUserId(),
                    text == null ? "" : text);
        } catch (LeadBlockedException e) {
            statusDerivation.markBlocked(bot.id(), lead.platformUserId());
            activityRecorder.record(PlatformActorType.MERCHANT, merchantId, PlatformAction.LEAD_BLOCKED,
                    "LEAD", leadId, merchantId, Map.of("reason", "blocked_by_user"));
            meterRegistry.counter("foxcash_crm_lead_blocked_total").increment();
            throw e;
        } catch (com.foxcash.domain.exception.TelegramApiException e) {
            throw new MessageSendFailedException("Provedor rejeitou o envio: " + e.getMessage(), e);
        }
        ChatMessage sent = messageRepository.save(ChatMessage.outbound(leadId, bot.id(), text, mediaUrl, clock.instant()));
        activityRecorder.record(PlatformActorType.MERCHANT, merchantId, PlatformAction.CHAT_MESSAGE_SENT,
                "LEAD", leadId, merchantId, Map.of("sender", "ADMIN", "hasText", text != null && !text.isBlank()));
        meterRegistry.counter("foxcash_crm_messages_sent_total").increment();
        return sent;
    }

    /** Marca todas as mensagens {@code CLIENT} como lidas (Spec 83 C11). */
    @Transactional
    public void markRead(UUID merchantId, UUID leadId) {
        get(merchantId, leadId);
        messageRepository.markAllRead(leadId);
    }

    /** Histórico paginado do chat (Spec 83 D9). */
    @Transactional(readOnly = true)
    public PageResult<ChatMessage> history(UUID merchantId, UUID leadId, int page, int size) {
        get(merchantId, leadId);
        return messageRepository.findByLeadId(leadId, page, size);
    }

    /** Contagem de mensagens {@code CLIENT} não lidas do lead (Spec 83 C11). */
    @Transactional(readOnly = true)
    public long unreadCount(UUID merchantId, UUID leadId) {
        get(merchantId, leadId);
        return messageRepository.countByLeadIdAndReadFalse(leadId);
    }

    /** Persiste mensagem recebida do cliente (webhook/listener) + SSE (Spec 83 D7). */
    public void persistInbound(UUID merchantId, UUID botId, String platformUserId,
                               String text, String mediaUrl) {
        leadRepository.findIdByBotIdAndPlatformUserId(botId, platformUserId)
                .ifPresent(leadId -> {
                    ChatMessage saved = messageRepository.save(
                            ChatMessage.inbound(leadId, botId, text, mediaUrl, clock.instant()));
                    meterRegistry.counter("foxcash_crm_messages_received_total").increment();
                    eventPublisher.publish(merchantId,
                            new ChatEventPublisher.ChatEvent("message", leadId, saved));
                });
    }
}
