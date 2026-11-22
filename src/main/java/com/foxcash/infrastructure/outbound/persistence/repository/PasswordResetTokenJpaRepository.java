package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.infrastructure.outbound.persistence.entity.PasswordResetTokenJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio Spring Data (infraestrutura) para tokens de recuperação de senha
 * (Spec 40).
 */
public interface PasswordResetTokenJpaRepository
        extends JpaRepository<PasswordResetTokenJpaEntity, UUID> {

    Optional<PasswordResetTokenJpaEntity> findByTokenHash(String tokenHash);

    /** Apaga os tokens abertos (não usados) do lojista — um ativo por lojista. */
    @Modifying
    @Query("delete from PasswordResetTokenJpaEntity t where t.merchantId = :merchantId and t.usedAt is null")
    void deleteOpenByMerchantId(@Param("merchantId") UUID merchantId);

    /**
     * SEC-10: consume ATÔMICO (UPDATE condicional) — devolve o nº de linhas
     * afetadas (1 = consumiu; 0 = já usado/inexistente/expirado).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PasswordResetTokenJpaEntity t set t.usedAt = :now "
            + "where t.tokenHash = :tokenHash and t.usedAt is null and t.expiresAt > :now")
    int consumeIfOpen(@Param("tokenHash") String tokenHash, @Param("now") Instant now);
}
