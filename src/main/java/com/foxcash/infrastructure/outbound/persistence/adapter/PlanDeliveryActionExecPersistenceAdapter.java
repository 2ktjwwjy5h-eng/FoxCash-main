package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.delivery.DeliveryActionExec;
import com.foxcash.domain.port.out.PlanDeliveryActionExecRepository;
import com.foxcash.infrastructure.outbound.persistence.mapper.PlanDeliveryMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.PlanDeliveryActionExecJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port de ações de entrega em EXECUÇÃO (Spec 49).
 */
@Repository
public class PlanDeliveryActionExecPersistenceAdapter implements PlanDeliveryActionExecRepository {

    private final PlanDeliveryActionExecJpaRepository jpaRepository;

    public PlanDeliveryActionExecPersistenceAdapter(PlanDeliveryActionExecJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public DeliveryActionExec save(DeliveryActionExec exec) {
        return PlanDeliveryMapper.toDomain(
                jpaRepository.save(PlanDeliveryMapper.toEntity(exec)));
    }

    @Override
    public List<DeliveryActionExec> saveAll(List<DeliveryActionExec> execs) {
        return jpaRepository.saveAll(execs.stream().map(PlanDeliveryMapper::toEntity).toList())
                .stream().map(PlanDeliveryMapper::toDomain).toList();
    }

    @Override
    public List<DeliveryActionExec> findByDeliveryIdOrderBySeq(UUID deliveryId) {
        return jpaRepository.findByDeliveryIdOrderBySeqAsc(deliveryId)
                .stream().map(PlanDeliveryMapper::toDomain).toList();
    }

    @Override
    public List<DeliveryActionExec> findByDeliveryIdOrderBySeqForUpdate(UUID deliveryId) {
        return jpaRepository.findByDeliveryIdForUpdate(deliveryId)
                .stream().map(PlanDeliveryMapper::toDomain).toList();
    }

    @Override
    public Optional<DeliveryActionExec> findByIdAndDeliveryId(UUID execId, UUID deliveryId) {
        return jpaRepository.findByIdAndDeliveryId(execId, deliveryId)
                .map(PlanDeliveryMapper::toDomain);
    }
}
