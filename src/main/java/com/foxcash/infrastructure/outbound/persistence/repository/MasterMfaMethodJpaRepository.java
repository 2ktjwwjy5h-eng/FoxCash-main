package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.infrastructure.outbound.persistence.entity.MasterMfaMethodJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio Spring Data (infraestrutura) para métodos de MFA do master (Spec 44).
 */
public interface MasterMfaMethodJpaRepository extends JpaRepository<MasterMfaMethodJpaEntity, UUID> {

    Optional<MasterMfaMethodJpaEntity> findFirstByMasterIdAndStatus(UUID masterId, String status);
}
