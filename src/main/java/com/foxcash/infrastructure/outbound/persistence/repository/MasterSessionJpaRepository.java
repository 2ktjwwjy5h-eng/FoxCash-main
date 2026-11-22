package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.infrastructure.outbound.persistence.entity.MasterSessionJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio Spring Data (infraestrutura) para sessoes do master.
 */
public interface MasterSessionJpaRepository extends JpaRepository<MasterSessionJpaEntity, UUID> {

    Optional<MasterSessionJpaEntity> findByTokenHash(String tokenHash);

    /**
     * Revoga (marca {@code revoked_at}) as sessões ativas do master (SEC-25) e
     * limpa {@code stepped_up_at} (Spec 69 — janela de step-up morre com a
     * sessão).
     */
    @Modifying
    @Query("update MasterSessionJpaEntity s set s.revokedAt = :now, s.steppedUpAt = null "
            + "where s.masterId = :masterId and s.revokedAt is null")
    void revokeAllByMasterId(@Param("masterId") UUID masterId, @Param("now") Instant now);

    /** Registra a re-prova de posse (Spec 69) na sessão ativa. */
    @Modifying
    @Query("update MasterSessionJpaEntity s set s.steppedUpAt = :now "
            + "where s.id = :sessionId and s.revokedAt is null")
    void markSteppedUp(@Param("sessionId") UUID sessionId, @Param("now") Instant now);
}
