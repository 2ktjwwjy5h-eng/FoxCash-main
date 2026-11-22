package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.infrastructure.outbound.persistence.entity.MerchantJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio Spring Data (infraestrutura) para lojistas.
 */
public interface MerchantJpaRepository extends JpaRepository<MerchantJpaEntity, UUID>,
        JpaSpecificationExecutor<MerchantJpaEntity> {

    /** Busca por e-mail (Spec 39) — o chamador normaliza em minúsculas. */
    Optional<MerchantJpaEntity> findByEmail(String email);

    Optional<MerchantJpaEntity> findByApiKeyHash(String apiKeyHash);

    List<MerchantJpaEntity> findByStatus(MerchantStatus status);

    long countByStatus(MerchantStatus status);

    /** Leitura com lock pessimista de escrita (fluxos de dinheiro do wallet). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MerchantJpaEntity m where m.id = :id")
    Optional<MerchantJpaEntity> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Limpa o hash da chave legada (Spec 12) quando casa com a chave revogada
     * (SEC-24): UPDATE condicional — só zera {@code api_key_hash} se o hash
     * persistido é o MESMO da chave gerenciável sendo revogada.
     */
    @Modifying
    @Query("update MerchantJpaEntity m set m.apiKeyHash = null "
            + "where m.id = :merchantId and m.apiKeyHash = :keyHash")
    void clearLegacyApiKeyHashIfMatches(@Param("merchantId") UUID merchantId,
                                        @Param("keyHash") String keyHash);
}
