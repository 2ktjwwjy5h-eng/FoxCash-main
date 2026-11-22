package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.withdrawal.WithdrawalDestination;
import com.foxcash.domain.port.out.WithdrawalDestinationRepository;
import com.foxcash.infrastructure.outbound.persistence.mapper.WithdrawalDestinationMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.WithdrawalDestinationJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link WithdrawalDestinationRepository} (Spec 31).
 */
@Repository
public class WithdrawalDestinationPersistenceAdapter implements WithdrawalDestinationRepository {

    private static final String ACTIVE = "ACTIVE";

    private final WithdrawalDestinationJpaRepository jpaRepository;

    public WithdrawalDestinationPersistenceAdapter(WithdrawalDestinationJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public WithdrawalDestination save(WithdrawalDestination destination) {
        return WithdrawalDestinationMapper.toDomain(
                jpaRepository.save(WithdrawalDestinationMapper.toEntity(destination)));
    }

    @Override
    public Optional<WithdrawalDestination> findByIdAndMerchantId(UUID id, UUID merchantId) {
        return jpaRepository.findByIdAndMerchantId(id, merchantId)
                .map(WithdrawalDestinationMapper::toDomain);
    }

    @Override
    public List<WithdrawalDestination> findByMerchantId(UUID merchantId) {
        return jpaRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId).stream()
                .map(WithdrawalDestinationMapper::toDomain)
                .toList();
    }

    @Override
    public long countActiveByMerchantId(UUID merchantId) {
        return jpaRepository.countByMerchantIdAndStatus(merchantId, ACTIVE);
    }
}
