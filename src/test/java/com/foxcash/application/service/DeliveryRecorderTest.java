package com.foxcash.application.service;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.delivery.DeliveryActionStatus;
import com.foxcash.domain.model.delivery.PlanDelivery;
import com.foxcash.domain.model.delivery.PlanDeliveryStatus;
import com.foxcash.domain.model.plan.DeliveryAction;
import com.foxcash.domain.model.plan.DeliveryActionType;
import com.foxcash.domain.model.plan.Plan;
import com.foxcash.domain.model.plan.Platform;
import com.foxcash.domain.model.store.Store;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.port.out.PlanDeliveryActionExecRepository;
import com.foxcash.domain.port.out.PlanDeliveryActionRepository;
import com.foxcash.domain.port.out.PlanDeliveryRepository;
import com.foxcash.domain.port.out.PlanRepository;
import com.foxcash.domain.port.out.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link DeliveryRecorder} (Spec 49): transação de plano APROVADA
 * cria a entrega PENDING + snapshot das ações na MESMA transação; replay →
 * no-op; plano sem ações → DELIVERED direto; transação avulsa → nada.
 */
class DeliveryRecorderTest {

    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID PLAN_ID = UUID.randomUUID();
    private static final UUID TX_ID = UUID.randomUUID();

    private final StoreRepository storeRepository = mock(StoreRepository.class);
    private final PlanRepository planRepository = mock(PlanRepository.class);
    private final PlanDeliveryActionRepository actionRepository = mock(PlanDeliveryActionRepository.class);
    private final PlanDeliveryRepository deliveryRepository = mock(PlanDeliveryRepository.class);
    private final PlanDeliveryActionExecRepository execRepository = mock(PlanDeliveryActionExecRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private DeliveryRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new DeliveryRecorder(storeRepository, planRepository, actionRepository,
                deliveryRepository, execRepository, clock);
        when(storeRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(store()));
        when(planRepository.findByIdAndStoreId(PLAN_ID, STORE_ID)).thenReturn(Optional.of(plan()));
        when(deliveryRepository.findByTransactionId(TX_ID)).thenReturn(Optional.empty());
    }

    private Store store() {
        return Store.restore(STORE_ID, MERCHANT_ID, "Bella", null, null, null, null,
                null, null, null, null, NOW, NOW);
    }

    private Plan plan() {
        return Plan.restore(PLAN_ID, STORE_ID, "VIP", null, Money.of("39.90"),
                null, null, false, true, NOW, NOW);
    }

    private PixTransaction approvedPlanTransaction() {
        return PixTransaction.restore(TX_ID, MERCHANT_ID, Money.of("39.90"), "VIP",
                null, "E123", "E123", null, null, TransactionStatus.APPROVED,
                NOW, NOW, NOW, "buyer-hash", "key-1", PLAN_ID, Platform.DISCORD, "user-1");
    }

    @Test
    void createsDeliveryWithSnapshotOfActions() {
        when(actionRepository.findByPlanIdOrderBySeq(PLAN_ID)).thenReturn(List.of(
                DeliveryAction.create(PLAN_ID, 0, DeliveryActionType.GRANT_ROLE, "{\"roleId\":\"r1\"}", NOW),
                DeliveryAction.create(PLAN_ID, 1, DeliveryActionType.SEND_MESSAGE, "{\"text\":\"oi\"}", NOW)));
        when(deliveryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        recorder.recordFor(approvedPlanTransaction());

        verify(deliveryRepository).save(argThat(d ->
                d.storeId().equals(STORE_ID)
                        && d.platform() == Platform.DISCORD
                        && "user-1".equals(d.platformUserId())
                        && d.status() == PlanDeliveryStatus.PENDING));
        verify(execRepository).saveAll(any());
    }

    @Test
    void replayOfPaidIsNoOp() {
        when(deliveryRepository.findByTransactionId(TX_ID))
                .thenReturn(Optional.of(PlanDelivery.restore(UUID.randomUUID(), STORE_ID, PLAN_ID, TX_ID,
                        Platform.DISCORD, "user-1", null, PlanDeliveryStatus.PENDING, NOW, null)));

        recorder.recordFor(approvedPlanTransaction());

        verify(deliveryRepository, never()).save(any());
        verify(execRepository, never()).saveAll(any());
    }

    @Test
    void planWithoutActionsDeliversDirectly() {
        when(actionRepository.findByPlanIdOrderBySeq(PLAN_ID)).thenReturn(List.of());
        when(deliveryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        recorder.recordFor(approvedPlanTransaction());

        verify(deliveryRepository).save(argThat(d -> d.status() == PlanDeliveryStatus.DELIVERED));
        verify(execRepository, never()).saveAll(any());
    }

    @Test
    void standaloneChargeIsIgnored() {
        PixTransaction standalone = PixTransaction.restore(TX_ID, MERCHANT_ID, Money.of("10.00"),
                "Avulsa", null, "E1", "E1", null, null, TransactionStatus.APPROVED,
                NOW, NOW, NOW, null, "k", null, null, null);

        recorder.recordFor(standalone);

        verify(deliveryRepository, never()).save(any());
    }

    private PlanDelivery argThat(java.util.function.Predicate<PlanDelivery> predicate) {
        return org.mockito.ArgumentMatchers.argThat(
                (org.mockito.ArgumentMatcher<PlanDelivery>) predicate::test);
    }
}
