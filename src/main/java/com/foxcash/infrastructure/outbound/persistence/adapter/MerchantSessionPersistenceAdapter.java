package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.merchant.MerchantSession;
import com.foxcash.domain.port.out.MerchantSessionRepository;
import com.foxcash.infrastructure.outbound.persistence.mapper.MerchantSessionMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.MerchantSessionJpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link MerchantSessionRepository} (Spec 20).
 */
@Repository
public class MerchantSessionPersistenceAdapter implements MerchantSessionRepository {

    private final MerchantSessionJpaRepository jpaRepository;

    public MerchantSessionPersistenceAdapter(MerchantSessionJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public MerchantSession save(MerchantSession session) {
        return MerchantSessionMapper.toDomain(jpaRepository.save(MerchantSessionMapper.toEntity(session)));
    }

    @Override
    public Optional<MerchantSession> findByTokenHash(String tokenHash) {
        return jpaRepository.findByTokenHash(tokenHash).map(MerchantSessionMapper::toDomain);
    }

    @Override
    public void revokeAllByMerchantId(UUID merchantId, Instant now) {
        jpaRepository.revokeAllByMerchantId(merchantId, now);
    }
}
