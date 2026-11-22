package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.infrastructure.outbound.persistence.entity.MasterTotpJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio Spring Data (infraestrutura) para o detalhe TOTP do MFA (Spec 44).
 */
public interface MasterTotpJpaRepository extends JpaRepository<MasterTotpJpaEntity, UUID> {

    Optional<MasterTotpJpaEntity> findById(UUID methodId);

    /** Leitura com lock pessimista de escrita (SEC-01) — incremento atômico de
     * falhas de TOTP sob concorrência. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from MasterTotpJpaEntity t where t.methodId = :methodId")
    Optional<MasterTotpJpaEntity> findByIdForUpdate(@Param("methodId") UUID methodId);
}
