package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.infrastructure.outbound.persistence.entity.MerchantSessionJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio Spring Data (infraestrutura) para sessoes do portal do lojista.
 */
public interface MerchantSessionJpaRepository extends JpaRepository<MerchantSessionJpaEntity, UUID> {

    Optional<MerchantSessionJpaEntity> findByTokenHash(String tokenHash);

    /** Revoga (marca {@code revoked_at}) as sessões ativas do lojista (Spec 40). */
    @Modifying
    @Query("update MerchantSessionJpaEntity s set s.revokedAt = :now "
            + "where s.merchantId = :merchantId and s.revokedAt is null")
    void revokeAllByMerchantId(@Param("merchantId") UUID merchantId, @Param("now") Instant now);
}
