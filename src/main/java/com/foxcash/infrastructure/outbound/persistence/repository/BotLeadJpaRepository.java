package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.infrastructure.outbound.persistence.entity.BotLeadJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositório Spring Data (infraestrutura) para leads de bot (Spec 75) e as
 * consultas do CRM (Specs 83/90 — busca/filtros/summary por lojista via join
 * com {@code store_bots}). Filtros dinâmicos via {@link JpaSpecificationExecutor}
 * (mesmo padrão do MasterAuditPersistenceAdapter — Spec 89 §1: predicates só
 * entram quando o filtro é não-nulo; {@code ? IS NULL OR} quebrava no Postgres
 * por inferência de tipo).
 */
public interface BotLeadJpaRepository extends JpaRepository<BotLeadJpaEntity, UUID>,
        JpaSpecificationExecutor<BotLeadJpaEntity> {

    Optional<BotLeadJpaEntity> findByBotIdAndPlatformUserId(UUID botId, String platformUserId);

    long countByBotIdAndFirstSeenAtBetween(UUID botId, Instant from, Instant to);

    long countByFirstSeenAtBetween(Instant from, Instant to);

    /**
     * Leads de todos os bots de um lojista (métricas do portal, Spec 75/D7) —
     * join com {@code store_bots} (a tabela {@code bot_leads} não tem
     * {@code merchant_id}; o vínculo é via {@code bot_id}).
     */
    @Query("SELECT COUNT(l) FROM BotLeadJpaEntity l " +
            "WHERE l.botId IN (SELECT b.id FROM StoreBotJpaEntity b WHERE b.merchantId = :merchantId) " +
            "AND l.firstSeenAt BETWEEN :from AND :to")
    long countByMerchantIdAndFirstSeenAtBetween(@Param("merchantId") UUID merchantId,
                                                @Param("from") Instant from,
                                                @Param("to") Instant to);

    /** Leads por bot no período (métricas do portal por bot, Spec 75/D7). */
    @Query("SELECT l.botId, COUNT(l) FROM BotLeadJpaEntity l " +
            "WHERE l.botId IN :botIds AND l.firstSeenAt BETWEEN :from AND :to GROUP BY l.botId")
    List<Object[]> countGroupedByBotId(@Param("botIds") List<UUID> botIds,
                                       @Param("from") Instant from,
                                       @Param("to") Instant to);

    // ---- CRM (Specs 83/90) ----
    // Busca/summary do CRM usam JpaSpecificationExecutor (Specification
    // condicional — predicates só entram com filtro não-nulo; ver javadoc).

    /** Lead por id com escopo do lojista (404 cross-lojista — Spec 83 C3). */
    @Query("SELECT l FROM BotLeadJpaEntity l WHERE l.id = :id AND l.botId IN " +
            "(SELECT b.id FROM StoreBotJpaEntity b WHERE b.merchantId = :merchantId)")
    Optional<BotLeadJpaEntity> findByIdAndMerchantId(@Param("merchantId") UUID merchantId,
                                                     @Param("id") UUID id);

    /**
     * Leads recentes do lojista para o shell (Spec 101) — ordenados pela última
     * interação ({@code updated_at}; fallback {@code created_at}) desc. O sort
     * COALESCE garante ordem estável mesmo para leads criados antes da V57
     * (updated_at NULL). {@code botIds} (Spec 124) opcional: null → todos os
     * bots do lojista; lista vazia → {@code IN ()} → vazio (header inválido
     * nunca vaza).
     */
    @Query("SELECT l FROM BotLeadJpaEntity l WHERE l.botId IN " +
            "(SELECT b.id FROM StoreBotJpaEntity b WHERE b.merchantId = :merchantId) " +
            "AND (:botIds IS NULL OR l.botId IN :botIds) " +
            "ORDER BY COALESCE(l.updatedAt, l.createdAt) DESC")
    List<BotLeadJpaEntity> findRecentByMerchantId(@Param("merchantId") UUID merchantId,
                                                  @Param("botIds") List<UUID> botIds,
                                                  org.springframework.data.domain.Pageable pageable);

    // ---- Spec 119 (CRM enriquecido) ----

    /** Última interação do lead (Spec 119 — coluna "Última interação"): updated_at com fallback created_at. */
    @Query("SELECT COALESCE(l.updatedAt, l.createdAt) FROM BotLeadJpaEntity l WHERE l.id = :id")
    Optional<Instant> findLastInteractionAt(@Param("id") UUID id);

    /**
     * Nome do plano da última compra APPROVED do lead (Spec 119 — coluna
     * "Plano"): o vínculo lead→transação é o funil {@code bot_funnel_events}
     * ({@code (bot_id, platform_user_id) → transaction_id}, evento PAID) — a
     * coluna {@code pix_transactions.platform_user_id} é criptografada em
     * repouso (IV aleatório) e nunca casa por igualdade. Mais recente primeiro
     * (pageable com size=1). Vazio quando o lead nunca comprou um plano (ou a
     * compra não foi aprovada).
     */
    @Query("SELECT p.name FROM BotFunnelEventJpaEntity f " +
            "JOIN PixTransactionJpaEntity t ON t.id = f.transactionId " +
            "JOIN PlanJpaEntity p ON p.id = t.planId " +
            "WHERE f.botId = :botId AND f.platformUserId = :platformUserId " +
            "AND f.event = com.foxcash.domain.model.botfunnel.FunnelEvent.PAID " +
            "AND t.status = com.foxcash.domain.model.transaction.TransactionStatus.APPROVED " +
            "AND t.planId IS NOT NULL " +
            "ORDER BY t.createdAt DESC")
    List<String> findLastPlanName(@Param("botId") UUID botId,
                                  @Param("platformUserId") String platformUserId,
                                  org.springframework.data.domain.Pageable pageable);
}
