package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.merchant.MerchantMfaMethod;
import com.foxcash.domain.port.out.MerchantMfaMethodRepository;
import com.foxcash.infrastructure.outbound.persistence.mapper.MerchantMfaMethodMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.MerchantMfaMethodJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link MerchantMfaMethodRepository} (Spec 94).
 */
@Repository
public class MerchantMfaMethodPersistenceAdapter implements MerchantMfaMethodRepository {

    private final MerchantMfaMethodJpaRepository jpaRepository;

    public MerchantMfaMethodPersistenceAdapter(MerchantMfaMethodJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public MerchantMfaMethod save(MerchantMfaMethod method) {
        return MerchantMfaMethodMapper.toDomain(
                jpaRepository.save(MerchantMfaMethodMapper.toEntity(method)));
    }

    @Override
    public Optional<MerchantMfaMethod> findActiveByMerchantId(UUID merchantId) {
        return jpaRepository.findFirstByMerchantIdAndStatus(merchantId, "ACTIVE")
                .map(MerchantMfaMethodMapper::toDomain);
    }

    @Override
    public Optional<MerchantMfaMethod> findPendingByMerchantId(UUID merchantId) {
        return jpaRepository.findFirstByMerchantIdAndStatus(merchantId, "PENDING")
                .map(MerchantMfaMethodMapper::toDomain);
    }

    @Override
    public void deleteByMerchantId(UUID merchantId) {
        jpaRepository.findFirstByMerchantIdAndStatus(merchantId, "ACTIVE")
                .ifPresent(jpaRepository::delete);
        jpaRepository.findFirstByMerchantIdAndStatus(merchantId, "PENDING")
                .ifPresent(jpaRepository::delete);
    }
}
