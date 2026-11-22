package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.domain.model.delivery.PlanDeliveryStatus;
import com.foxcash.infrastructure.outbound.persistence.entity.PlanDeliveryJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositório Spring Data (infraestrutura) para entregas de plano (Spec 49).
 */
public interface PlanDeliveryJpaRepository extends JpaRepository<PlanDeliveryJpaEntity, UUID> {

    Optional<PlanDeliveryJpaEntity> findByIdAndStoreId(UUID deliveryId, UUID storeId);

    List<PlanDeliveryJpaEntity> findByStoreIdAndStatusOrderByCreatedAtAsc(UUID storeId, PlanDeliveryStatus status);

    Optional<PlanDeliveryJpaEntity> findByTransactionId(UUID transactionId);

    /**
     * Compradores da loja com entrega entregue (Spec 51) — platform_user_id
     * deduplicados (o JPA decifra o CryptoConverter na leitura; em claro apenas
     * ao dono da API key).
     */
    @Query("select distinct d.platformUserId from PlanDeliveryJpaEntity d "
            + "where d.storeId = :storeId and d.status = :status")
    List<String> findDistinctPlatformUserIdsByStoreIdAndStatus(
            @Param("storeId") UUID storeId, @Param("status") PlanDeliveryStatus status);
}
