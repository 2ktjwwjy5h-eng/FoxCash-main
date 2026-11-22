package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.domain.model.storebot.BotStatus;
import com.foxcash.infrastructure.outbound.persistence.entity.StoreBotJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositório Spring Data (infraestrutura) para bots de venda (Spec 74).
 */
public interface StoreBotJpaRepository extends JpaRepository<StoreBotJpaEntity, UUID> {

    Optional<StoreBotJpaEntity> findByIdAndMerchantId(UUID id, UUID merchantId);

    List<StoreBotJpaEntity> findByMerchantIdAndDeletedAtIsNullOrderByCreatedAtAsc(UUID merchantId);

    // Spec 124: frota do perfil (bots não-removidos do lojista+perfil). O
    // includeUnassigned é TRUE quando o perfil é o DEFAULT do lojista — bots com
    // profile_id NULL (legado/registro novo, Spec 122) pertencem LOGICAMENTE ao
    // default e precisam aparecer na frota e no bot-set (achado de auditoria
    // 2026-08-15: bots-panel/CRM/broadcasts/tracking vazios com o default ativo).
    @Query("SELECT sb FROM StoreBotJpaEntity sb "
            + "WHERE sb.merchantId = :merchantId AND sb.deletedAt IS NULL "
            + "AND (sb.profileId = :profileId "
            + "     OR (:includeUnassigned = true AND sb.profileId IS NULL)) "
            + "ORDER BY sb.createdAt ASC")
    List<StoreBotJpaEntity> findFleetByMerchantIdAndProfile(
            @Param("merchantId") UUID merchantId,
            @Param("profileId") UUID profileId,
            @Param("includeUnassigned") boolean includeUnassigned);

    // Spec 124 (T1): ids dos bots do perfil para o escopo por bot-set — subquery
    // com merchant_id (perfil de outro lojista → vazio, nunca vaza). MESMO
    // includeUnassigned do findFleetByMerchantIdAndProfile (default → NULL bots).
    @Query("SELECT sb.id FROM StoreBotJpaEntity sb "
            + "WHERE sb.merchantId = :merchantId AND sb.deletedAt IS NULL "
            + "AND (sb.profileId = :profileId "
            + "     OR (:includeUnassigned = true AND sb.profileId IS NULL))")
    List<UUID> findIdsByMerchantIdAndProfile(
            @Param("merchantId") UUID merchantId,
            @Param("profileId") UUID profileId,
            @Param("includeUnassigned") boolean includeUnassigned);

    Optional<StoreBotJpaEntity> findByWebhookSecretHash(String webhookSecretHash);

    Optional<StoreBotJpaEntity> findByTokenHash(String tokenHash);

    List<StoreBotJpaEntity> findByDeletedAtIsNullOrderByCreatedAtAsc();

    List<StoreBotJpaEntity> findByStatusAndDeletedAtIsNull(BotStatus status);
}
