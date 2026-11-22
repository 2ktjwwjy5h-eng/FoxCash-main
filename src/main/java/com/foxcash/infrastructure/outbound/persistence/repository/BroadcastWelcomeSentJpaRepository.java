package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.infrastructure.outbound.persistence.entity.BroadcastWelcomeSentJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repositório Spring Data (infraestrutura) da idempotência do WELCOME (Spec
 * 79/D6) — UNIQUE {@code (chat_id, user_id)}: evento de entrada duplicado não
 * dispara a mensagem 2×.
 */
public interface BroadcastWelcomeSentJpaRepository
        extends JpaRepository<BroadcastWelcomeSentJpaEntity, UUID> {

    Optional<BroadcastWelcomeSentJpaEntity> findByChatIdAndUserId(String chatId, String userId);
}
