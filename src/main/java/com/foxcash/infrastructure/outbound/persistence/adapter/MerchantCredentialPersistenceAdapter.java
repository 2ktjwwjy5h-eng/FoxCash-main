package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.merchant.MerchantCredential;
import com.foxcash.domain.port.out.MerchantCredentialRepository;
import com.foxcash.infrastructure.outbound.persistence.mapper.MerchantCredentialMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.MerchantCredentialJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link MerchantCredentialRepository} (Spec 20).
 */
@Repository
public class MerchantCredentialPersistenceAdapter implements MerchantCredentialRepository {

    private final MerchantCredentialJpaRepository jpaRepository;

    public MerchantCredentialPersistenceAdapter(MerchantCredentialJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public MerchantCredential save(MerchantCredential credential) {
        return MerchantCredentialMapper.toDomain(jpaRepository.save(MerchantCredentialMapper.toEntity(credential)));
    }

    @Override
    public Optional<MerchantCredential> findByMerchantId(UUID merchantId) {
        return jpaRepository.findByMerchantId(merchantId).map(MerchantCredentialMapper::toDomain);
    }

    @Override
    public Optional<MerchantCredential> findByMerchantIdForUpdate(UUID merchantId) {
        return jpaRepository.findByMerchantIdForUpdate(merchantId).map(MerchantCredentialMapper::toDomain);
    }
}
