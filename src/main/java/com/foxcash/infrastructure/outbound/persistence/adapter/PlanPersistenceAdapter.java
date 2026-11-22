package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.plan.DeliveryAction;
import com.foxcash.domain.model.plan.Plan;
import com.foxcash.domain.port.out.PlanDeliveryActionRepository;
import com.foxcash.domain.port.out.PlanRepository;
import com.foxcash.infrastructure.outbound.persistence.mapper.PlanMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.PlanDeliveryActionJpaRepository;
import com.foxcash.infrastructure.outbound.persistence.repository.PlanJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA dos ports de plano e ações configuradas (Spec 49).
 */
@Repository
public class PlanPersistenceAdapter implements PlanRepository, PlanDeliveryActionRepository {

    private final PlanJpaRepository planJpaRepository;
    private final PlanDeliveryActionJpaRepository actionJpaRepository;

    public PlanPersistenceAdapter(PlanJpaRepository planJpaRepository,
                                  PlanDeliveryActionJpaRepository actionJpaRepository) {
        this.planJpaRepository = planJpaRepository;
        this.actionJpaRepository = actionJpaRepository;
    }

    @Override
    public Plan save(Plan plan) {
        return PlanMapper.toDomain(planJpaRepository.save(PlanMapper.toEntity(plan)));
    }

    @Override
    public Optional<Plan> findByIdAndStoreId(UUID planId, UUID storeId) {
        return planJpaRepository.findByIdAndStoreId(planId, storeId).map(PlanMapper::toDomain);
    }

    @Override
    public List<Plan> findByStoreId(UUID storeId) {
        return planJpaRepository.findByStoreIdOrderByCreatedAtDesc(storeId)
                .stream().map(PlanMapper::toDomain).toList();
    }

    @Override
    public List<Plan> findActiveByStoreId(UUID storeId) {
        return planJpaRepository.findByStoreIdAndActiveTrueOrderByCreatedAtDesc(storeId)
                .stream().map(PlanMapper::toDomain).toList();
    }

    @Override
    public DeliveryAction save(DeliveryAction action) {
        return PlanMapper.toDomain(actionJpaRepository.save(PlanMapper.toEntity(action)));
    }

    @Override
    public List<DeliveryAction> findByPlanIdOrderBySeq(UUID planId) {
        return actionJpaRepository.findByPlanIdOrderBySeqAsc(planId)
                .stream().map(PlanMapper::toDomain).toList();
    }

    @Override
    public Optional<DeliveryAction> findByIdAndPlanId(UUID actionId, UUID planId) {
        return actionJpaRepository.findByIdAndPlanId(actionId, planId).map(PlanMapper::toDomain);
    }

    @Override
    public Optional<DeliveryAction> findById(UUID actionId) {
        return actionJpaRepository.findById(actionId).map(PlanMapper::toDomain);
    }

    @Override
    public boolean existsByPlanId(UUID planId) {
        return actionJpaRepository.existsByPlanId(planId);
    }

    @Override
    public void delete(DeliveryAction action) {
        actionJpaRepository.delete(PlanMapper.toEntity(action));
    }
}
