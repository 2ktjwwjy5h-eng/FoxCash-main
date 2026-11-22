package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.domain.model.botfunnel.FunnelEvent;
import com.foxcash.infrastructure.outbound.persistence.entity.BotFunnelEventJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositório Spring Data (infraestrutura) para eventos de funil de bot (Spec 75).
 */
public interface BotFunnelEventJpaRepository extends JpaRepository<BotFunnelEventJpaEntity, UUID> {

    /** Idempotência do {@code checkout_started}: um evento por (bot, transação). */
    Optional<BotFunnelEventJpaEntity> findByBotIdAndTransactionId(UUID botId, UUID transactionId);

    /** Todos os eventos de funil de uma transação (Spec 125 — trace). */
    List<BotFunnelEventJpaEntity> findByTransactionId(UUID transactionId);

    /**
     * Idempotência do {@code paid}: um evento {@code PAID} por (bot, transação) —
     * específico por evento para não confundir com o {@code CHECKOUT_STARTED} da
     * mesma transação (Spec 75/F1).
     */
    Optional<BotFunnelEventJpaEntity> findByBotIdAndTransactionIdAndEvent(
            UUID botId, UUID transactionId, FunnelEvent event);

    /** Contagem de eventos de um tipo por bot (métricas, Spec 75/D7). */
    long countByBotIdAndEvent(UUID botId, FunnelEvent event);

    /**
     * Eventos de um tipo dos bots de um lojista no período (métricas do portal,
     * Spec 75/D7) — join com {@code store_bots} (o vínculo é via {@code bot_id}).
     */
    @Query("SELECT COUNT(e) FROM BotFunnelEventJpaEntity e " +
            "WHERE e.botId IN (SELECT b.id FROM StoreBotJpaEntity b WHERE b.merchantId = :merchantId) " +
            "AND e.event = :event AND e.occurredAt BETWEEN :from AND :to")
    long countByMerchantIdAndEventBetween(@Param("merchantId") UUID merchantId,
                                          @Param("event") FunnelEvent event,
                                          @Param("from") java.time.Instant from,
                                          @Param("to") java.time.Instant to);

    /** Contagem de eventos de um tipo por bot no período (métricas por bot). */
    @Query("SELECT e.botId, COUNT(e) FROM BotFunnelEventJpaEntity e " +
            "WHERE e.botId IN :botIds AND e.event = :event AND e.occurredAt BETWEEN :from AND :to " +
            "GROUP BY e.botId")
    List<Object[]> countGroupedByBotId(@Param("botIds") List<UUID> botIds,
                                       @Param("event") FunnelEvent event,
                                       @Param("from") java.time.Instant from,
                                       @Param("to") java.time.Instant to);
}
