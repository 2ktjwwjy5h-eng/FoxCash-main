package com.foxcash.application.service;

import com.foxcash.domain.exception.PlanInactiveException;
import com.foxcash.domain.exception.PlanNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.plan.Plan;
import com.foxcash.domain.model.plan.Platform;
import com.foxcash.domain.model.store.Store;
import com.foxcash.domain.port.in.CreatePixChargeUseCase;
import com.foxcash.domain.port.in.PlanPurchaseUseCase;
import com.foxcash.domain.port.out.PlanRepository;
import com.foxcash.domain.port.out.StoreBotRepository;
import com.foxcash.domain.port.out.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
 * Testes da compra de plano (Spec 49): cria a cobrança vinculada ao plano com
 * o destinatário; plano inativo → 409; inexistente → 404; platform ausente →
 * 400; auditoria PLAN_PURCHASED.
 */
class PlanPurchaseServiceTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID PLAN_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");

    private final StoreRepository storeRepository = mock(StoreRepository.class);
    private final PlanRepository planRepository = mock(PlanRepository.class);
    private final StoreBotRepository botRepository = mock(StoreBotRepository.class);
    private final CreatePixChargeUseCase createPixCharge = mock(CreatePixChargeUseCase.class);
    private final BotFunnelService botFunnelService = mock(BotFunnelService.class);
    private final PlatformActivityRecorder activityRecorder = mock(PlatformActivityRecorder.class);

    private PlanPurchaseService service;

    @BeforeEach
    void setUp() {
        service = new PlanPurchaseService(storeRepository, planRepository, botRepository,
                createPixCharge, botFunnelService, activityRecorder);
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

    private PlanPurchaseUseCase.PurchaseCommand command() {
        return new PlanPurchaseUseCase.PurchaseCommand(3600, "buyer-1", "key-1",
                Platform.DISCORD, "123456789012345678");
    }

    @Test
    void purchaseCreatesChargeWithPlanAndRecipient() {
        when(planRepository.findByIdAndStoreId(PLAN_ID, STORE_ID)).thenReturn(Optional.of(plan(true)));
        when(createPixCharge.create(any())).thenReturn(new CreatePixChargeUseCase.Result(UUID.randomUUID()));

        PlanPurchaseUseCase.Result result = service.purchase(MERCHANT_ID, PLAN_ID, command());

        assertThat(result.transactionId()).isNotNull();
        verify(createPixCharge).create(argThat(cmd ->
                cmd.planId().equals(PLAN_ID)
                        && cmd.platform() == Platform.DISCORD
                        && "123456789012345678".equals(cmd.platformUserId())
                        && cmd.merchantId().equals(MERCHANT_ID)));
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.PLAN_PURCHASED), eq("PLAN"), eq(PLAN_ID), eq(MERCHANT_ID), any());
    }

    @Test
    void purchaseInactivePlanThrows409() {
        when(planRepository.findByIdAndStoreId(PLAN_ID, STORE_ID)).thenReturn(Optional.of(plan(false)));

        assertThatThrownBy(() -> service.purchase(MERCHANT_ID, PLAN_ID, command()))
                .isInstanceOf(PlanInactiveException.class);
        verify(createPixCharge, never()).create(any());
    }

    @Test
    void purchaseUnknownPlanThrows404() {
        when(planRepository.findByIdAndStoreId(PLAN_ID, STORE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.purchase(MERCHANT_ID, PLAN_ID, command()))
                .isInstanceOf(PlanNotFoundException.class);
        verify(createPixCharge, never()).create(any());
    }

    @Test
    void purchaseWithoutPlatformThrows400() {
        when(planRepository.findByIdAndStoreId(PLAN_ID, STORE_ID)).thenReturn(Optional.of(plan(true)));
        PlanPurchaseUseCase.PurchaseCommand noPlatform = new PlanPurchaseUseCase.PurchaseCommand(
                3600, null, "key-1", null, "user-1");

        assertThatThrownBy(() -> service.purchase(MERCHANT_ID, PLAN_ID, noPlatform))
                .isInstanceOf(IllegalArgumentException.class);
        verify(createPixCharge, never()).create(any());
    }

    @Test
    void purchaseWithoutPlatformUserIdThrows400() {
        when(planRepository.findByIdAndStoreId(PLAN_ID, STORE_ID)).thenReturn(Optional.of(plan(true)));
        PlanPurchaseUseCase.PurchaseCommand noUser = new PlanPurchaseUseCase.PurchaseCommand(
                3600, null, "key-1", Platform.TELEGRAM, "  ");

        assertThatThrownBy(() -> service.purchase(MERCHANT_ID, PLAN_ID, noUser))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private CreatePixChargeUseCase.Command argThat(java.util.function.Predicate<CreatePixChargeUseCase.Command> predicate) {
        return org.mockito.ArgumentMatchers.argThat(
                (org.mockito.ArgumentMatcher<CreatePixChargeUseCase.Command>) predicate::test);
    }
}
