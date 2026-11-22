package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.crm.ChatMessage;
import com.foxcash.domain.model.crm.ChatSenderType;
import com.foxcash.infrastructure.outbound.persistence.entity.ChatMessageJpaEntity;

/**
 * Mapeia entre o modelo de domínio {@link ChatMessage} e a entidade JPA
 * {@link ChatMessageJpaEntity} (Spec 83).
 */
public final class ChatMessageMapper {

    private ChatMessageMapper() {
    }

    public static ChatMessageJpaEntity toEntity(ChatMessage message) {
        return new ChatMessageJpaEntity(message.id(), message.leadId(), message.botId(),
                message.senderType().name(), message.textContent(), message.mediaUrl(),
                message.read(), message.createdAt());
    }

    public static ChatMessage toDomain(ChatMessageJpaEntity entity) {
        return ChatMessage.restore(entity.getId(), entity.getLeadId(), entity.getBotId(),
                ChatSenderType.valueOf(entity.getSenderType()), entity.getTextContent(),
                entity.getMediaUrl(), entity.isRead(), entity.getCreatedAt());
    }
}
