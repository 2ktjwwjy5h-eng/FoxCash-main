package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.fraud.Chargeback;
import com.foxcash.domain.port.out.ChargebackRepository;
import com.foxcash.infrastructure.outbound.persistence.mapper.ChargebackMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.ChargebackJpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link ChargebackRepository} (Spec 19).
 */
@Repository
public class ChargebackPersistenceAdapter implements ChargebackRepository {

    private final ChargebackJpaRepository jpaRepository;

    public ChargebackPersistenceAdapter(ChargebackJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Chargeback save(Chargeback chargeback) {
        return ChargebackMapper.toDomain(jpaRepository.save(ChargebackMapper.toEntity(chargeback)));
    }

    @Override
    public Optional<Chargeback> findById(UUID id) {
        return jpaRepository.findById(id).map(ChargebackMapper::toDomain);
    }

    @Override
    public Optional<Chargeback> findByTransactionId(UUID transactionId) {
        return jpaRepository.findByTransactionId(transactionId).map(ChargebackMapper::toDomain);
    }

    @Override
    public long countByMerchantId(UUID merchantId) {
        return jpaRepository.countByMerchantId(merchantId);
    }

    @Override
    public long countByMerchantIdAndCreatedAtAfter(UUID merchantId, Instant since) {
        return jpaRepository.countByMerchantIdAndCreatedAtAfter(merchantId, since);
    }

    @Override
    public long countByBuyerIdHashSince(String buyerIdHash, Instant since) {
        return jpaRepository.countByBuyerIdHashAndCreatedAtAfter(buyerIdHash, since);
    }

    @Override
    public List<Chargeback> findRecent(int limit) {
        return jpaRepository.findTop100ByOrderByCreatedAtDesc().stream()
                .limit(limit)
                .map(ChargebackMapper::toDomain)
                .toList();
    }
}
