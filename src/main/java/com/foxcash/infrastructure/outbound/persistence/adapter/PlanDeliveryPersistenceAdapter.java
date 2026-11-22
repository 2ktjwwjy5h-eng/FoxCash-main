package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.delivery.PlanDelivery;
import com.foxcash.domain.model.delivery.PlanDeliveryStatus;
import com.foxcash.domain.port.out.PlanDeliveryRepository;
import com.foxcash.infrastructure.outbound.persistence.mapper.PlanDeliveryMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.PlanDeliveryJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port de entregas de plano (Spec 49).
 */
@Repository
public class PlanDeliveryPersistenceAdapter implements PlanDeliveryRepository {

    private final PlanDeliveryJpaRepository jpaRepository;

    public PlanDeliveryPersistenceAdapter(PlanDeliveryJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public PlanDelivery save(PlanDelivery delivery) {
        return PlanDeliveryMapper.toDomain(
                jpaRepository.save(PlanDeliveryMapper.toEntity(delivery)));
    }

    @Override
    public Optional<PlanDelivery> findByIdAndStoreId(UUID deliveryId, UUID storeId) {
        return jpaRepository.findByIdAndStoreId(deliveryId, storeId)
                .map(PlanDeliveryMapper::toDomain);
    }

    @Override
    public List<PlanDelivery> findByStoreIdAndStatusPending(UUID storeId) {
        return jpaRepository.findByStoreIdAndStatusOrderByCreatedAtAsc(
                        storeId, PlanDeliveryStatus.PENDING)
                .stream().map(PlanDeliveryMapper::toDomain).toList();
    }

    @Override
    public Optional<PlanDelivery> findByTransactionId(UUID transactionId) {
        return jpaRepository.findByTransactionId(transactionId)
                .map(PlanDeliveryMapper::toDomain);
    }

    @Override
    public List<String> findDistinctDeliveredPlatformUserIds(UUID storeId) {
        return jpaRepository.findDistinctPlatformUserIdsByStoreIdAndStatus(
                storeId, PlanDeliveryStatus.DELIVERED);
    }
}
