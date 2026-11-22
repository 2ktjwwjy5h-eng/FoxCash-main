package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.auth.PasswordResetToken;
import com.foxcash.domain.port.out.PasswordResetTokenRepository;
import com.foxcash.infrastructure.outbound.persistence.mapper.PasswordResetTokenMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.PasswordResetTokenJpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link PasswordResetTokenRepository} (Spec 40).
 */
@Repository
public class PasswordResetTokenPersistenceAdapter implements PasswordResetTokenRepository {

    private final PasswordResetTokenJpaRepository jpaRepository;

    public PasswordResetTokenPersistenceAdapter(PasswordResetTokenJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public PasswordResetToken save(PasswordResetToken token) {
        return PasswordResetTokenMapper.toDomain(
                jpaRepository.save(PasswordResetTokenMapper.toEntity(token)));
    }

    @Override
    public Optional<PasswordResetToken> findByTokenHash(String tokenHash) {
        return jpaRepository.findByTokenHash(tokenHash).map(PasswordResetTokenMapper::toDomain);
    }

    @Override
    public void deleteOpenByMerchantId(UUID merchantId) {
        jpaRepository.deleteOpenByMerchantId(merchantId);
    }

    @Override
    public boolean consumeIfOpen(String tokenHash, Instant now) {
        return jpaRepository.consumeIfOpen(tokenHash, now) > 0;
    }
}
