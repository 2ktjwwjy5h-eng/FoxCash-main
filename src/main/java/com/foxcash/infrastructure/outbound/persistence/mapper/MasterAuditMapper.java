package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.audit.MasterAuditEntry;
import com.foxcash.infrastructure.outbound.persistence.entity.MasterAuditJpaEntity;

/**
 * Mapeia entre o modelo de dominio MasterAuditEntry e a entidade JPA
 * MasterAuditJpaEntity (Spec 37).
 */
public final class MasterAuditMapper {

    private MasterAuditMapper() {
    }

    public static MasterAuditJpaEntity toEntity(MasterAuditEntry entry) {
        return new MasterAuditJpaEntity(
                entry.id(),
                entry.actorId(),
                entry.action(),
                entry.merchantId(),
                entry.referenceType(),
                entry.referenceId(),
                entry.beforeJson(),
                entry.afterJson(),
                entry.reason(),
                entry.ip(),
                entry.createdAt());
    }

    public static MasterAuditEntry toDomain(MasterAuditJpaEntity entity) {
        return new MasterAuditEntry(
                entity.getId(),
                entity.getActorId(),
                entity.getAction(),
                entity.getMerchantId(),
                entity.getReferenceType(),
                entity.getReferenceId(),
                entity.getBeforeJson(),
                entity.getAfterJson(),
                entity.getReason(),
                entity.getIp(),
                entity.getCreatedAt());
    }
}
