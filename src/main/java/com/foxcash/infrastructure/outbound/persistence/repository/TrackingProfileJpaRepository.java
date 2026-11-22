package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.infrastructure.outbound.persistence.entity.TrackingProfileJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositório JPA do perfil de tracking (Spec 81).
 */
public interface TrackingProfileJpaRepository extends JpaRepository<TrackingProfileJpaEntity, UUID> {

    Optional<TrackingProfileJpaEntity> findByIdAndMerchantId(UUID id, UUID merchantId);

    List<TrackingProfileJpaEntity> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId);

    /**
     * Spec 124 (filtro rígido por bot-set): perfis do lojista com pelo menos um
     * bot vinculado ({@code tracking_profile_bots}) no bot-set do perfil ativo.
     * Lista vazia → vazio (header inválido nunca vaza).
     */
    @Query("SELECT DISTINCT p FROM TrackingProfileJpaEntity p JOIN TrackingProfileBotJpaEntity pb "
            + "ON pb.id.profileId = p.id "
            + "WHERE p.merchantId = :merchantId AND pb.id.botId IN :botIds "
            + "ORDER BY p.createdAt DESC")
    List<TrackingProfileJpaEntity> findByMerchantIdAndBotIds(@Param("merchantId") UUID merchantId,
                                                             @Param("botIds") List<UUID> botIds);

    /** Dispatch (Spec 81/D5.1): perfis ACTIVE vinculados a um bot. */
    @Query("SELECT p FROM TrackingProfileJpaEntity p JOIN TrackingProfileBotJpaEntity pb "
            + "ON pb.id.profileId = p.id WHERE pb.id.botId = :botId AND p.status = 'ACTIVE'")
    List<TrackingProfileJpaEntity> findActiveByBotId(@Param("botId") UUID botId);
}
