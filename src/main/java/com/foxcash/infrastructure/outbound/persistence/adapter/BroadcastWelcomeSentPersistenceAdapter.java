package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.port.out.BroadcastWelcomeSentRepository;
import com.foxcash.infrastructure.outbound.persistence.entity.BroadcastWelcomeSentJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.repository.BroadcastWelcomeSentJpaRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link BroadcastWelcomeSentRepository} (Spec 79/D6) —
 * idempotência do modo WELCOME por {@code (chat_id, user_id)} (UNIQUE).
 */
@Repository
public class BroadcastWelcomeSentPersistenceAdapter implements BroadcastWelcomeSentRepository {

    private final BroadcastWelcomeSentJpaRepository jpaRepository;

    public BroadcastWelcomeSentPersistenceAdapter(BroadcastWelcomeSentJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public boolean existsByChatIdAndUserId(String chatId, String userId) {
        return jpaRepository.findByChatIdAndUserId(chatId, userId).isPresent();
    }

    @Override
    public void record(UUID broadcastId, String chatId, String userId) {
        // Raça concorrente (evento duplicado chegando em paralelo): a UNIQUE
        // serializa — o insert perdedor vira no-op (padrão Spec 49: replay
        // concorrente executa 1×).
        try {
            jpaRepository.save(new BroadcastWelcomeSentJpaEntity(
                    UUID.randomUUID(), broadcastId, chatId, userId, Instant.now()));
        } catch (DataIntegrityViolationException e) {
            // já registrado — idempotente
        }
    }
}
