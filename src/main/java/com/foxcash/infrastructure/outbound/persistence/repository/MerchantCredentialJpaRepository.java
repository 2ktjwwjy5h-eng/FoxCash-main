package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.infrastructure.outbound.persistence.entity.MerchantCredentialJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio Spring Data (infraestrutura) para credenciais do portal.
 */
public interface MerchantCredentialJpaRepository extends JpaRepository<MerchantCredentialJpaEntity, UUID> {

    Optional<MerchantCredentialJpaEntity> findByMerchantId(UUID merchantId);

    /** Leitura com lock pessimista de escrita (SEC-01) — registro atômico de
     * falha de login sob concorrência. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from MerchantCredentialJpaEntity c where c.merchantId = :merchantId")
    Optional<MerchantCredentialJpaEntity> findByMerchantIdForUpdate(@Param("merchantId") UUID merchantId);
}
