package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.domain.model.profile.ProfileScope;
import com.foxcash.infrastructure.outbound.persistence.entity.MerchantProfileJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositório Spring Data (infraestrutura) para perfis de operação (Spec 122).
 * Perfis não-deletados por padrão (soft-delete); escrita com lock pessimista.
 */
public interface MerchantProfileJpaRepository extends JpaRepository<MerchantProfileJpaEntity, UUID> {

    List<MerchantProfileJpaEntity> findByMerchantIdAndDeletedAtIsNullOrderBySortOrderAsc(UUID merchantId);

    Optional<MerchantProfileJpaEntity> findByIdAndDeletedAtIsNull(UUID id);

    Optional<MerchantProfileJpaEntity> findByIdAndMerchantIdAndDeletedAtIsNull(UUID id, UUID merchantId);

    Optional<MerchantProfileJpaEntity> findByMerchantIdAndIsDefaultTrueAndDeletedAtIsNull(UUID merchantId);

    /** Escrita com lock pessimista (SEC-19 — PATCH de rota/nome). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from MerchantProfileJpaEntity p where p.id = :id")
    Optional<MerchantProfileJpaEntity> findByIdForUpdate(@Param("id") UUID id);

    /** Rota de gateway do perfil (projection — hot path do RoutingPolicyResolver). */
    @Query("select p.gatewayRoute from MerchantProfileJpaEntity p "
            + "where p.merchantId = :merchantId and p.id = :profileId and p.deletedAt is null")
    Optional<String> findGatewayRouteByMerchantIdAndId(@Param("merchantId") UUID merchantId,
                                                       @Param("profileId") UUID profileId);

    /** Escopo do perfil (Spec 139 — projection, hot path do RoutingPolicyResolver). */
    @Query("select p.scope from MerchantProfileJpaEntity p "
            + "where p.merchantId = :merchantId and p.id = :profileId and p.deletedAt is null")
    Optional<ProfileScope> findScopeByMerchantIdAndId(@Param("merchantId") UUID merchantId,
                                                      @Param("profileId") UUID profileId);
}
