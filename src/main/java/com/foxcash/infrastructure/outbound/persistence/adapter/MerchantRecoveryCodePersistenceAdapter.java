package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.merchant.MerchantRecoveryCode;
import com.foxcash.domain.port.out.MerchantRecoveryCodeRepository;
import com.foxcash.infrastructure.outbound.persistence.mapper.MerchantRecoveryCodeMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.MerchantRecoveryCodeJpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link MerchantRecoveryCodeRepository} (Spec 94).
 */
@Repository
public class MerchantRecoveryCodePersistenceAdapter implements MerchantRecoveryCodeRepository {

    private final MerchantRecoveryCodeJpaRepository jpaRepository;

    public MerchantRecoveryCodePersistenceAdapter(MerchantRecoveryCodeJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public List<MerchantRecoveryCode> saveAll(List<MerchantRecoveryCode> codes) {
        return jpaRepository.saveAll(codes.stream().map(MerchantRecoveryCodeMapper::toEntity).toList())
                .stream().map(MerchantRecoveryCodeMapper::toDomain).toList();
    }

    @Override
    public List<MerchantRecoveryCode> findByMethodId(UUID methodId) {
        return jpaRepository.findByMethodId(methodId).stream()
                .map(MerchantRecoveryCodeMapper::toDomain).toList();
    }

    @Override
    public boolean consumeIfOpen(UUID methodId, String codeHash, Instant now) {
        return jpaRepository.consumeIfOpen(methodId, codeHash, now) > 0;
    }

    @Override
    public void deleteByMethodId(UUID methodId) {
        jpaRepository.deleteByMethodId(methodId);
    }
}
