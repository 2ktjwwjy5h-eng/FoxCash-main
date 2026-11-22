package com.foxcash.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.domain.exception.PlanNotFoundException;
import com.foxcash.domain.exception.StoreNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.plan.DeliveryAction;
import com.foxcash.domain.model.plan.DeliveryActionType;
import com.foxcash.domain.model.plan.Plan;
import com.foxcash.domain.model.store.Store;
import com.foxcash.domain.port.in.PlanManagementUseCase;
import com.foxcash.domain.port.in.PlanManagementUseCase.PlanView;
import com.foxcash.domain.port.in.PlanManagementUseCase.SaveActionCommand;
import com.foxcash.domain.port.in.PlanManagementUseCase.SavePlanCommand;
import com.foxcash.domain.port.out.PlanDeliveryActionRepository;
import com.foxcash.domain.port.out.PlanRepository;
import com.foxcash.domain.port.out.StoreRepository;
import com.foxcash.infrastructure.config.PortalProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do serviço de planos do portal (Spec 49): CRUD de planos (visual),
 * preço ≤ teto, flag hasDeliveryActions na listagem, CRUD de ações com payload
 * validado por tipo, 404 cross-loja e auditoria PLAN_CREATED.
 */
class PlanServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID PLAN_ID = UUID.randomUUID();

    private final StoreRepository storeRepository = mock(StoreRepository.class);
    private final PlanRepository planRepository = mock(PlanRepository.class);
    private final PlanDeliveryActionRepository actionRepository = mock(PlanDeliveryActionRepository.class);
    private final PlatformActivityRecorder activityRecorder = mock(PlatformActivityRecorder.class);
    private final PortalProperties portalProperties = new PortalProperties();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private PlanService service;

    @BeforeEach
    void setUp() {
        PlanActionPayloadCodec codec = new PlanActionPayloadCodec(new ObjectMapper());
        service = new PlanService(storeRepository, planRepository, actionRepository, codec,
                activityRecorder, portalProperties, clock);
        when(storeRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(store()));
    }

    private Store store() {
        return Store.restore(STORE_ID, MERCHANT_ID, "Bella", null, null, null, null,
                null, null, null, null, NOW, NOW);
    }

    private Plan plan(boolean active) {
        return Plan.restore(PLAN_ID, STORE_ID, "VIP Bronze", "Desc", Money.of("39.90"),
                null, "#8E44AD", false, active, NOW, NOW);
    }

    private SavePlanCommand planCommand() {
        return new SavePlanCommand("VIP Bronze", new BigDecimal("39.90"), "Desc", null, "#8E44AD", true);
    }

    @Test
    void createSavesPlanAndRecordsActivity() {
        when(planRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Plan created = service.create(MERCHANT_ID, planCommand());

        assertThat(created.storeId()).isEqualTo(STORE_ID);
        assertThat(created.name()).isEqualTo("VIP Bronze");
        assertThat(created.price().amount().toString()).isEqualTo("39.90");
        assertThat(created.active()).isTrue();
        verify(planRepository).save(any());
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.PLAN_CREATED), eq("PLAN"), any(), eq(MERCHANT_ID), any());
    }

    @Test
    void createRejectsPriceAboveCeiling() {
        portalProperties.setChargeMaxAmount(new BigDecimal("100.00"));
        SavePlanCommand command = new SavePlanCommand("X", new BigDecimal("100.01"), null, null, null, false);

        assertThatThrownBy(() -> service.create(MERCHANT_ID, command))
                .isInstanceOf(IllegalArgumentException.class);
        verify(planRepository, never()).save(any());
    }

    @Test
    void listReturnsPlansWithHasDeliveryActionsFlag() {
        when(planRepository.findByStoreId(STORE_ID)).thenReturn(List.of(plan(true), plan(false)));
        when(actionRepository.existsByPlanId(PLAN_ID)).thenReturn(true);

        List<PlanView> views = service.list(MERCHANT_ID);

        assertThat(views).hasSize(2);
        assertThat(views.get(0).hasDeliveryActions()).isTrue();
    }

    @Test
    void getPlanOfAnotherStoreThrows404() {
        when(planRepository.findByIdAndStoreId(PLAN_ID, STORE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(MERCHANT_ID, PLAN_ID))
                .isInstanceOf(PlanNotFoundException.class);
    }

    @Test
    void updateChangesFields() {
        when(planRepository.findByIdAndStoreId(PLAN_ID, STORE_ID)).thenReturn(Optional.of(plan(true)));
        when(planRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Plan updated = service.update(MERCHANT_ID, PLAN_ID,
                new SavePlanCommand("VIP Ouro", new BigDecimal("79.90"), null, null, null, null));

        assertThat(updated.name()).isEqualTo("VIP Ouro");
        assertThat(updated.price().amount().toString()).isEqualTo("79.90");
        assertThat(updated.description()).isEqualTo("Desc");
    }

    @Test
    void setActiveDeactivates() {
        when(planRepository.findByIdAndStoreId(PLAN_ID, STORE_ID)).thenReturn(Optional.of(plan(true)));
        when(planRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.setActive(MERCHANT_ID, PLAN_ID, false);

        verify(planRepository).save(argThatActive(false));
    }

    private Plan argThatActive(boolean active) {
        return org.mockito.ArgumentMatchers.argThat(p -> p.active() == active);
    }

    @Test
    void addActionValidatesPayloadPerType() {
        when(planRepository.findByIdAndStoreId(PLAN_ID, STORE_ID)).thenReturn(Optional.of(plan(true)));
        when(actionRepository.findByPlanIdOrderBySeq(PLAN_ID)).thenReturn(List.of());
        when(actionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DeliveryAction action = service.addAction(MERCHANT_ID, PLAN_ID,
                new SaveActionCommand(0, DeliveryActionType.SEND_LINK,
                        "{\"url\":\"https://t.me/+abc\",\"label\":\"Grupo VIP\"}"));

        assertThat(action.actionType()).isEqualTo(DeliveryActionType.SEND_LINK);
        assertThat(action.seq()).isZero();
        assertThat(action.payload()).contains("https://t.me/+abc");
    }

    @Test
    void addActionRejectsPayloadMissingRequiredKey() {
        when(planRepository.findByIdAndStoreId(PLAN_ID, STORE_ID)).thenReturn(Optional.of(plan(true)));
        when(actionRepository.findByPlanIdOrderBySeq(PLAN_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.addAction(MERCHANT_ID, PLAN_ID,
                new SaveActionCommand(0, DeliveryActionType.GRANT_ROLE, "{}")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(actionRepository, never()).save(any());
    }

    @Test
    void addActionRejectsDuplicateSeq() {
        when(planRepository.findByIdAndStoreId(PLAN_ID, STORE_ID)).thenReturn(Optional.of(plan(true)));
        when(actionRepository.findByPlanIdOrderBySeq(PLAN_ID)).thenReturn(
                List.of(DeliveryAction.create(PLAN_ID, 0, DeliveryActionType.SEND_MESSAGE,
                        "{\"text\":\"oi\"}", NOW)));

        assertThatThrownBy(() -> service.addAction(MERCHANT_ID, PLAN_ID,
                new SaveActionCommand(0, DeliveryActionType.SEND_LINK, "{\"url\":\"https://x.com\"}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateActionKeepsIdAndUpdatesValues() {
        UUID actionId = UUID.randomUUID();
        DeliveryAction existing = DeliveryAction.restore(actionId, PLAN_ID, 0,
                DeliveryActionType.SEND_MESSAGE, "{\"text\":\"oi\"}", NOW);
        when(planRepository.findByIdAndStoreId(PLAN_ID, STORE_ID)).thenReturn(Optional.of(plan(true)));
        when(actionRepository.findById(actionId)).thenReturn(Optional.of(existing));
        when(actionRepository.findByPlanIdOrderBySeq(PLAN_ID)).thenReturn(List.of(existing));
        when(actionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DeliveryAction updated = service.updateAction(MERCHANT_ID, actionId,
                new SaveActionCommand(1, DeliveryActionType.SEND_LINK, "{\"url\":\"https://x.com\"}"));

        assertThat(updated.id()).isEqualTo(actionId);
        assertThat(updated.seq()).isEqualTo(1);
        assertThat(updated.actionType()).isEqualTo(DeliveryActionType.SEND_LINK);
    }

    @Test
    void updateActionOfAnotherStoreThrows404() {
        when(actionRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateAction(MERCHANT_ID, UUID.randomUUID(),
                new SaveActionCommand(0, DeliveryActionType.SEND_MESSAGE, "{\"text\":\"oi\"}")))
                .isInstanceOf(PlanNotFoundException.class);
    }

    @Test
    void deleteActionRemoves() {
        UUID actionId = UUID.randomUUID();
        DeliveryAction existing = DeliveryAction.restore(actionId, PLAN_ID, 0,
                DeliveryActionType.SEND_MESSAGE, "{\"text\":\"oi\"}", NOW);
        when(planRepository.findByIdAndStoreId(PLAN_ID, STORE_ID)).thenReturn(Optional.of(plan(true)));
        when(actionRepository.findById(actionId)).thenReturn(Optional.of(existing));

        service.deleteAction(MERCHANT_ID, actionId);

        verify(actionRepository).delete(existing);
    }

    @Test
    void withoutStoreThrows404() {
        when(storeRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.list(MERCHANT_ID))
                .isInstanceOf(StoreNotFoundException.class);
    }

    @Test
    void updateRejectsPriceAboveCeiling() {
        // L92/L93: update com preço acima do teto NÃO pode pular a validação
        // (mutantes que negam `newPrice != null` ou removem a chamada deixariam
        // o preço passar e salvar).
        when(planRepository.findByIdAndStoreId(PLAN_ID, STORE_ID)).thenReturn(Optional.of(plan(true)));

        assertThatThrownBy(() -> service.update(MERCHANT_ID, PLAN_ID,
                new SavePlanCommand("VIP Ouro", new BigDecimal("10000.01"), null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(planRepository, never()).save(any());
    }

    @Test
    void updateActionRejectsDuplicateSeq() {
        // L134: ensureSeqAvailable também roda no updateAction — seq já tomado
        // por OUTRA ação não pode passar (mutante remove a validação).
        UUID actionId = UUID.randomUUID();
        DeliveryAction existing = DeliveryAction.restore(actionId, PLAN_ID, 0,
                DeliveryActionType.SEND_MESSAGE, "{\"text\":\"oi\"}", NOW);
        DeliveryAction other = DeliveryAction.restore(UUID.randomUUID(), PLAN_ID, 1,
                DeliveryActionType.SEND_LINK, "{\"url\":\"https://x.com\"}", NOW);
        when(planRepository.findByIdAndStoreId(PLAN_ID, STORE_ID)).thenReturn(Optional.of(plan(true)));
        when(actionRepository.findById(actionId)).thenReturn(Optional.of(existing));
        when(actionRepository.findByPlanIdOrderBySeq(PLAN_ID)).thenReturn(List.of(existing, other));

        assertThatThrownBy(() -> service.updateAction(MERCHANT_ID, actionId,
                new SaveActionCommand(1, DeliveryActionType.SEND_LINK, "{\"url\":\"https://x.com\"}")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(actionRepository, never()).save(any());
    }

    @Test
    void validatePriceCeilingBoundaries() {
        // L169: price == 0 rejeitado (boundary `signum() <= 0` → `< 0`) — e o
        // save NÃO pode ser alcançado (mutante deixaria o preço passar).
        assertThatThrownBy(() -> service.create(MERCHANT_ID,
                new SavePlanCommand("Free", new BigDecimal("0.00"), null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(planRepository, never()).save(any());
        // L172: price == teto aceito (boundary `> max` → `>= max`).
        when(planRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Plan plan = service.create(MERCHANT_ID,
                new SavePlanCommand("Max", new BigDecimal("10000.00"), null, null, null, null));
        assertThat(plan.price().amount()).isEqualByComparingTo("10000.00");
    }

    @Test
    void getReturnsOwnedPlan() {
        // L69: get() devolve o plano do lojista (mutante NullReturnVals).
        when(planRepository.findByIdAndStoreId(PLAN_ID, STORE_ID)).thenReturn(Optional.of(plan(true)));

        assertThat(service.get(MERCHANT_ID, PLAN_ID).id()).isEqualTo(PLAN_ID);
    }

    @Test
    void setActiveActivatesPlan() {
        // L105: setActive(true) → plan.activate() (mutante remove a chamada).
        when(planRepository.findByIdAndStoreId(PLAN_ID, STORE_ID)).thenReturn(Optional.of(plan(false)));
        when(planRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.setActive(MERCHANT_ID, PLAN_ID, true);

        verify(planRepository).save(argThatActive(true));
    }

    @Test
    void listActionsReturnsOrderedActions() {
        // L116: listActions devolve as ações na ordem (mutante EmptyObjectReturnVals).
        when(planRepository.findByIdAndStoreId(PLAN_ID, STORE_ID)).thenReturn(Optional.of(plan(true)));
        DeliveryAction action = DeliveryAction.restore(UUID.randomUUID(), PLAN_ID, 0,
                DeliveryActionType.SEND_MESSAGE, "{\"text\":\"oi\"}", NOW);
        when(actionRepository.findByPlanIdOrderBySeq(PLAN_ID)).thenReturn(List.of(action));

        assertThat(service.listActions(MERCHANT_ID, PLAN_ID)).containsExactly(action);
    }
}
