package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.master.MasterSession;
import com.foxcash.domain.port.out.MasterSessionRepository;
import com.foxcash.infrastructure.outbound.persistence.mapper.MasterSessionMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.MasterSessionJpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link MasterSessionRepository} (Spec 13).
 */
@Repository
public class MasterSessionPersistenceAdapter implements MasterSessionRepository {

    private final MasterSessionJpaRepository jpaRepository;

    public MasterSessionPersistenceAdapter(MasterSessionJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public MasterSession save(MasterSession session) {
        return MasterSessionMapper.toDomain(jpaRepository.save(MasterSessionMapper.toEntity(session)));
    }

    @Override
    public Optional<MasterSession> findByTokenHash(String tokenHash) {
        return jpaRepository.findByTokenHash(tokenHash).map(MasterSessionMapper::toDomain);
    }

    @Override
    public void revokeAllByMasterId(UUID masterId, Instant now) {
        jpaRepository.revokeAllByMasterId(masterId, now);
    }

    @Override
    public void markSteppedUp(UUID sessionId, Instant now) {
        jpaRepository.markSteppedUp(sessionId, now);
    }
}
