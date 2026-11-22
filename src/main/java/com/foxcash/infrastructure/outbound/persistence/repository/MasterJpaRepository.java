package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.infrastructure.outbound.persistence.entity.MasterJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio Spring Data (infraestrutura) para perfis master.
 */
public interface MasterJpaRepository extends JpaRepository<MasterJpaEntity, UUID> {

    Optional<MasterJpaEntity> findByUsername(String username);

    /** Leitura com lock pessimista de escrita (SEC-01) — registro atômico de
     * falha de login sob concorrência. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MasterJpaEntity m where m.id = :id")
    Optional<MasterJpaEntity> findByIdForUpdate(@Param("id") UUID id);
}
