package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.domain.model.audit.MasterAuditAction;
import com.foxcash.infrastructure.outbound.persistence.entity.MasterAuditJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

/**
 * Repositorio Spring Data (infraestrutura) da trilha de auditoria do master —
 * Spec 37.
 */
public interface MasterAuditJpaRepository
        extends JpaRepository<MasterAuditJpaEntity, UUID>,
        JpaSpecificationExecutor<MasterAuditJpaEntity> {

    /** Verifica se existe alguma entrada para a acao (teste/backstop). */
    boolean existsByAction(MasterAuditAction action);
}
