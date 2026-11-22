package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.merchant.MerchantTotp;
import com.foxcash.domain.port.out.MerchantTotpRepository;
import com.foxcash.infrastructure.outbound.persistence.mapper.MerchantTotpMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.MerchantTotpJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link MerchantTotpRepository} (Spec 94).
 */
@Repository
public class MerchantTotpPersistenceAdapter implements MerchantTotpRepository {

    private final MerchantTotpJpaRepository jpaRepository;

    public MerchantTotpPersistenceAdapter(MerchantTotpJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public MerchantTotp save(MerchantTotp totp) {
        return MerchantTotpMapper.toDomain(jpaRepository.save(MerchantTotpMapper.toEntity(totp)));
    }

    @Override
    public Optional<MerchantTotp> findByMethodId(UUID methodId) {
        return jpaRepository.findById(methodId).map(MerchantTotpMapper::toDomain);
    }

    @Override
    public Optional<MerchantTotp> findByIdForUpdate(UUID methodId) {
        return jpaRepository.findByIdForUpdate(methodId).map(MerchantTotpMapper::toDomain);
    }
}
