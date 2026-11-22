package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.broadcast.Broadcast;
import com.foxcash.domain.model.broadcast.BroadcastButtons;
import com.foxcash.infrastructure.outbound.persistence.entity.BroadcastJpaEntity;

/**
 * Mapeia entre o modelo de domínio {@link Broadcast} e a entidade JPA
 * {@link BroadcastJpaEntity} (Spec 79). O {@code buttons_json} (JSONB) é
 * transportado como JSON cru: o value object {@link BroadcastButtons} faz o
 * parse/validação na escrita; no restore, o JSONB já validado é apenas
 * re-hidratado estruturalmente (sem revalidar URL/limites).
 */
public final class BroadcastMapper {

    private BroadcastMapper() {
    }

    public static BroadcastJpaEntity toEntity(Broadcast broadcast) {
        return new BroadcastJpaEntity(
                broadcast.id(),
                broadcast.storeBotId(),
                broadcast.mode(),
                broadcast.targetChatId(),
                broadcast.content(),
                broadcast.mediaUrl(),
                broadcast.mediaType(),
                broadcast.mediaFileId(),
                broadcast.buttons() == null ? null : broadcast.buttons().json(),
                broadcast.status(),
                broadcast.errorMessage(),
                broadcast.scheduledAt(),
                broadcast.intervalSeconds(),
                broadcast.createdAt(),
                broadcast.updatedAt());
    }

    public static Broadcast toDomain(BroadcastJpaEntity entity) {
        return Broadcast.restore(
                entity.getId(),
                entity.getStoreBotId(),
                entity.getMode(),
                entity.getTargetChatId(),
                entity.getContent(),
                entity.getMediaUrl(),
                entity.getMediaType(),
                entity.getMediaFileId(),
                BroadcastButtons.restore(entity.getButtonsJson()),
                entity.getStatus(),
                entity.getErrorMessage(),
                entity.getScheduledAt(),
                entity.getIntervalSeconds(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
