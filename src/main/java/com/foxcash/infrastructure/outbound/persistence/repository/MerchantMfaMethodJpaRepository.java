package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.infrastructure.outbound.persistence.entity.MerchantMfaMethodJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio Spring Data (infraestrutura) para métodos de MFA do lojista no
 * portal (Spec 94). Espelho do {@code MasterMfaMethodJpaRepository} (Spec 44).
 */
public interface MerchantMfaMethodJpaRepository extends JpaRepository<MerchantMfaMethodJpaEntity, UUID> {

    Optional<MerchantMfaMethodJpaEntity> findFirstByMerchantIdAndStatus(UUID merchantId, String status);
}
