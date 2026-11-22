package com.foxcash.application.service;

import com.foxcash.domain.exception.PlanNotFoundException;
import com.foxcash.domain.exception.StoreNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.plan.DeliveryAction;
import com.foxcash.domain.model.plan.Plan;
import com.foxcash.domain.port.in.PlanManagementUseCase;
import com.foxcash.domain.port.out.PlanDeliveryActionRepository;
import com.foxcash.domain.port.out.PlanRepository;
import com.foxcash.domain.port.out.StoreRepository;
import com.foxcash.infrastructure.config.PortalProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aplicação: gestão de planos e ações de entrega pelo portal (Spec 49) — CRUD
 * de planos (visual) + CRUD de ações (payload validado por tipo e criptografado
 * em repouso). Tudo por {@code merchantId} do principal; a loja (1:1) é
 * resolvida internamente. Auditoria {@code PLAN_CREATED} (Spec 41).
 */
@Service
public class PlanService implements PlanManagementUseCase {

    private final StoreRepository storeRepository;
    private final PlanRepository planRepository;
    private final PlanDeliveryActionRepository actionRepository;
    private final PlanActionPayloadCodec payloadCodec;
    private final PlatformActivityRecorder activityRecorder;
    private final PortalProperties portalProperties;
    private final Clock clock;

    public PlanService(StoreRepository storeRepository,
                       PlanRepository planRepository,
                       PlanDeliveryActionRepository actionRepository,
                       PlanActionPayloadCodec payloadCodec,
                       PlatformActivityRecorder activityRecorder,
                       PortalProperties portalProperties,
                       Clock clock) {
        this.storeRepository = storeRepository;
        this.planRepository = planRepository;
        this.actionRepository = actionRepository;
        this.payloadCodec = payloadCodec;
        this.activityRecorder = activityRecorder;
        this.portalProperties = portalProperties;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PlanView> list(UUID merchantId) {
        UUID storeId = requireStore(merchantId);
        return planRepository.findByStoreId(storeId).stream()
                .map(plan -> new PlanView(plan, actionRepository.existsByPlanId(plan.id())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Plan get(UUID merchantId, UUID planId) {
        return requirePlan(merchantId, planId);
    }

    @Override
    @Transactional
    public Plan create(UUID merchantId, SavePlanCommand command) {
        UUID storeId = requireStore(merchantId);
        validatePriceCeiling(command.price());
        Plan plan = Plan.create(storeId, command.name(), Money.of(command.price()),
                command.description(), command.imageUrl(), command.embedColor(),
                Boolean.TRUE.equals(command.featured()), clock.instant());
        Plan saved = planRepository.save(plan);
        activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                PlatformAction.PLAN_CREATED, "PLAN", saved.id(), merchantId,
                Map.of("name", saved.name(), "price", saved.price().amount()));
        return saved;
    }

    @Override
    @Transactional
    public Plan update(UUID merchantId, UUID planId, SavePlanCommand command) {
        Plan plan = requirePlan(merchantId, planId);
        Money newPrice = command.price() != null ? Money.of(command.price()) : null;
        if (newPrice != null) {
            validatePriceCeiling(command.price());
        }
        plan.update(command.name(), newPrice, command.description(), command.imageUrl(),
                command.embedColor(), command.featured(), clock.instant());
        return planRepository.save(plan);
    }

    @Override
    @Transactional
    public void setActive(UUID merchantId, UUID planId, boolean active) {
        Plan plan = requirePlan(merchantId, planId);
        if (active) {
            plan.activate();
        } else {
            plan.deactivate();
        }
        planRepository.save(plan);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeliveryAction> listActions(UUID merchantId, UUID planId) {
        requirePlan(merchantId, planId);
        return actionRepository.findByPlanIdOrderBySeq(planId);
    }

    @Override
    @Transactional
    public DeliveryAction addAction(UUID merchantId, UUID planId, SaveActionCommand command) {
        requirePlan(merchantId, planId);
        ensureSeqAvailable(planId, command.seq(), null);
        String payload = payloadCodec.validateAndEncode(command.actionType(), payloadMap(command.payloadJson()));
        DeliveryAction action = DeliveryAction.create(planId, command.seq(), command.actionType(),
                payload, clock.instant());
        return actionRepository.save(action);
    }

    @Override
    @Transactional
    public DeliveryAction updateAction(UUID merchantId, UUID actionId, SaveActionCommand command) {
        DeliveryAction action = requireActionOfMerchant(merchantId, actionId);
        ensureSeqAvailable(action.planId(), command.seq(), actionId);
        String payload = payloadCodec.validateAndEncode(command.actionType(), payloadMap(command.payloadJson()));
        // Mesmo id (UPDATE via merge) — o id da ação permanece estável.
        return actionRepository.save(action.withValues(command.seq(), command.actionType(), payload));
    }

    @Override
    @Transactional
    public void deleteAction(UUID merchantId, UUID actionId) {
        DeliveryAction action = requireActionOfMerchant(merchantId, actionId);
        actionRepository.delete(action);
    }

    /** Ação do lojista (404 cross-loja sem revelar existência): a ação → plano → loja. */
    private DeliveryAction requireActionOfMerchant(UUID merchantId, UUID actionId) {
        DeliveryAction action = actionRepository.findById(actionId)
                .orElseThrow(PlanNotFoundException::new);
        requirePlan(merchantId, action.planId());
        return action;
    }

    private UUID requireStore(UUID merchantId) {
        return storeRepository.findByMerchantId(merchantId)
                .orElseThrow(StoreNotFoundException::new)
                .id();
    }

    private Plan requirePlan(UUID merchantId, UUID planId) {
        UUID storeId = requireStore(merchantId);
        return planRepository.findByIdAndStoreId(planId, storeId)
                .orElseThrow(PlanNotFoundException::new);
    }

    /** Teto de preço do plano (mesma regra das cobranças — {@code PortalProperties}). */
    private void validatePriceCeiling(BigDecimal price) {
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("price deve ser positivo");
        }
        if (price.compareTo(portalProperties.getChargeMaxAmount()) > 0) {
            throw new IllegalArgumentException(
                    "price acima do teto: " + portalProperties.getChargeMaxAmount());
        }
    }

    /** Rejeita seq duplicado (0..N — o vendedor define a ordem; sem colisão). */
    private void ensureSeqAvailable(UUID planId, int seq, UUID exceptActionId) {
        boolean taken = actionRepository.findByPlanIdOrderBySeq(planId).stream()
                .anyMatch(a -> a.seq() == seq && !a.id().equals(exceptActionId));
        if (taken) {
            throw new IllegalArgumentException("ja existe acao com seq " + seq);
        }
    }

    /** O payload chega como Map (DTO) — serializa para o comando. */
    private Map<String, Object> payloadMap(String payloadJson) {
        return payloadCodec.decode(payloadJson);
    }
}
