package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.withdrawal.AutoWithdrawConfig;
import com.foxcash.domain.port.out.AutoWithdrawConfigRepository;
import com.foxcash.infrastructure.outbound.persistence.mapper.AutoWithdrawConfigMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.AutoWithdrawConfigJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link AutoWithdrawConfigRepository} (Spec 70).
 */
@Repository
public class AutoWithdrawConfigPersistenceAdapter implements AutoWithdrawConfigRepository {

    private final AutoWithdrawConfigJpaRepository jpaRepository;

    public AutoWithdrawConfigPersistenceAdapter(AutoWithdrawConfigJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<AutoWithdrawConfig> findByMerchantId(UUID merchantId) {
        return jpaRepository.findByMerchantId(merchantId)
                .map(AutoWithdrawConfigMapper::toDomain);
    }

    @Override
    public List<AutoWithdrawConfig> findEnabled() {
        return jpaRepository.findByEnabledTrue().stream()
                .map(AutoWithdrawConfigMapper::toDomain)
                .toList();
    }

    @Override
    public AutoWithdrawConfig save(AutoWithdrawConfig config) {
        return AutoWithdrawConfigMapper.toDomain(
                jpaRepository.save(AutoWithdrawConfigMapper.toEntity(config)));
    }
}
