package com.foxcash.domain.port.out;

import com.foxcash.domain.model.botfunnel.BotFunnelEvent;
import com.foxcash.domain.model.botfunnel.FunnelEvent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída: persistência de eventos de funil de bot (Spec 75) — trilha
 * append-only. {@code paid} é idempotente por {@code (bot_id, transaction_id)}.
 */
public interface BotFunnelEventRepository {

    void save(BotFunnelEvent event);

    /** Idempotência do {@code checkout_started}: um evento por (bot, transação). */
    Optional<BotFunnelEvent> findByBotIdAndTransactionId(UUID botId, UUID transactionId);

    /** Todos os eventos de funil de uma transação (Spec 125 — trace: jornada do bot). */
    List<BotFunnelEvent> findByTransactionId(UUID transactionId);

    /**
     * Idempotência do {@code paid}: um evento {@code PAID} por (bot, transação) —
     * lookup específico por evento (o {@code CHECKOUT_STARTED} da mesma transação
     * NÃO bloqueia o {@code paid}, Spec 75/F1).
     */
    Optional<BotFunnelEvent> findByBotIdAndTransactionIdAndEvent(UUID botId, UUID transactionId,
                                                                 FunnelEvent event);

    /** Total de eventos de um tipo por bot (métricas, Spec 75). */
    long countByBotIdAndEvent(UUID botId, FunnelEvent event);

    /** Eventos de um tipo dos bots de um lojista no período (portal agregado). */
    long countByMerchantIdAndEventBetween(UUID merchantId, FunnelEvent event, Instant from, Instant to);

    /** Eventos de um tipo por bot no período — chave {@code bot_id} → contagem. */
    Map<UUID, Long> countGroupedByBotId(List<UUID> botIds, FunnelEvent event, Instant from, Instant to);
}
