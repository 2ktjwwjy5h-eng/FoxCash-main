package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.infrastructure.outbound.persistence.entity.PlatformActivityJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

/**
 * Repositorio Spring Data (infraestrutura) da trilha unificada de atividades
 * (Spec 41).
 *
 * <p>{@code deleteOlderThan} (Spec 95) é a única operação de escrita além do
 * save — usada APENAS pelo job de retenção opt-in
 * ({@code PlatformActivityCleanupScheduler}); a API não expõe mutação (contrato
 * append-only da Spec 41 preservado). O delete usa o índice
 * {@code ix_platform_activity_created} existente (V33).
 */
public interface PlatformActivityJpaRepository
        extends JpaRepository<PlatformActivityJpaEntity, UUID>,
        JpaSpecificationExecutor<PlatformActivityJpaEntity> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from PlatformActivityJpaEntity a where a.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
