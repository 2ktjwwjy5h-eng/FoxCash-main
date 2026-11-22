package com.foxcash.application.service;

import com.foxcash.domain.exception.DeliveryActionNotFoundException;
import com.foxcash.domain.exception.DeliveryNotFoundException;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.delivery.DeliveryActionExec;
import com.foxcash.domain.model.delivery.DeliveryActionStatus;
import com.foxcash.domain.model.delivery.PlanDelivery;
import com.foxcash.domain.model.delivery.PlanDeliveryStatus;
import com.foxcash.domain.model.plan.DeliveryActionType;
import com.foxcash.domain.model.plan.Platform;
import com.foxcash.domain.model.store.Store;
import com.foxcash.domain.port.out.PlanDeliveryActionExecRepository;
import com.foxcash.domain.port.out.PlanDeliveryRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes da execução de entregas (Spec 49): gate por ação (só PENDING),
 * replay 204 idempotente, transição automática (todas DONE → DELIVERED;
 * FAILED → FAILED), retry (reabre FAILED → PENDING) e 404 cross-loja.
 */
class DeliveryActionCompletionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID DELIVERY_ID = UUID.randomUUID();
    private static final UUID TX_ID = UUID.randomUUID();

    private final StoreRepository storeRepository = mock(StoreRepository.class);
    private final PlanDeliveryRepository deliveryRepository = mock(PlanDeliveryRepository.class);
    private final PlanDeliveryActionExecRepository execRepository = mock(PlanDeliveryActionExecRepository.class);
    private final PlatformActivityRecorder activityRecorder = mock(PlatformActivityRecorder.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private DeliveryActionCompletionService service;

    @BeforeEach
    void setUp() {
        service = new DeliveryActionCompletionService(storeRepository, deliveryRepository,
                execRepository, activityRecorder, clock);
        when(storeRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(store()));
        when(deliveryRepository.findByIdAndStoreId(DELIVERY_ID, STORE_ID))
                .thenReturn(Optional.of(delivery(PlanDeliveryStatus.PENDING)));
    }

    private Store store() {
        return Store.restore(STORE_ID, MERCHANT_ID, "Bella", null, null, null, null,
                null, null, null, null, NOW, NOW);
    }

    private PlanDelivery delivery(PlanDeliveryStatus status) {
        return PlanDelivery.restore(DELIVERY_ID, STORE_ID, UUID.randomUUID(), TX_ID,
                Platform.DISCORD, "user-1", null, status, NOW, null);
    }

    private DeliveryActionExec exec(int seq, DeliveryActionStatus status) {
        return DeliveryActionExec.restore(UUID.randomUUID(), DELIVERY_ID, seq,
                DeliveryActionType.SEND_MESSAGE, "{\"text\":\"oi\"}", status, null, null);
    }

    @Test
    void completingLastActionDelivers() {
        when(execRepository.findByDeliveryIdOrderBySeqForUpdate(DELIVERY_ID)).thenReturn(
                List.of(exec(0, DeliveryActionStatus.PENDING), exec(1, DeliveryActionStatus.DONE)));
        when(execRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.complete(MERCHANT_ID, DELIVERY_ID, 0, DeliveryActionStatus.DONE, null);

        verify(deliveryRepository).save(argThat(d -> d.status() == PlanDeliveryStatus.DELIVERED));
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.DELIVERY_ACTION_COMPLETED), eq("DELIVERY"), eq(DELIVERY_ID),
                eq(MERCHANT_ID), any());
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.DELIVERY_COMPLETED), eq("DELIVERY"), eq(DELIVERY_ID),
                eq(MERCHANT_ID), any());
    }

    @Test
    void failingAnyActionFailsDelivery() {
        when(execRepository.findByDeliveryIdOrderBySeqForUpdate(DELIVERY_ID)).thenReturn(
                List.of(exec(0, DeliveryActionStatus.PENDING), exec(1, DeliveryActionStatus.PENDING)));
        when(execRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.complete(MERCHANT_ID, DELIVERY_ID, 0, DeliveryActionStatus.FAILED, "erro no cargo");

        verify(deliveryRepository).save(argThat(d -> d.status() == PlanDeliveryStatus.FAILED));
    }

    @Test
    void replayOfCompletedActionIsNoOp() {
        // Ação já DONE — replay (gate) → no-op: nem salva nem transiciona.
        when(execRepository.findByDeliveryIdOrderBySeqForUpdate(DELIVERY_ID)).thenReturn(
                List.of(exec(0, DeliveryActionStatus.DONE)));

        service.complete(MERCHANT_ID, DELIVERY_ID, 0, DeliveryActionStatus.DONE, null);

        verify(execRepository, never()).save(any());
        verify(deliveryRepository, never()).save(any());
        verify(activityRecorder, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void completingUnknownActionThrows404() {
        when(execRepository.findByDeliveryIdOrderBySeqForUpdate(DELIVERY_ID)).thenReturn(List.of(exec(1, DeliveryActionStatus.PENDING)));

        assertThatThrownBy(() -> service.complete(MERCHANT_ID, DELIVERY_ID, 99,
                DeliveryActionStatus.DONE, null))
                .isInstanceOf(DeliveryActionNotFoundException.class);
    }

    @Test
    void deliveryOfAnotherStoreThrows404() {
        when(deliveryRepository.findByIdAndStoreId(DELIVERY_ID, STORE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.complete(MERCHANT_ID, DELIVERY_ID, 0,
                DeliveryActionStatus.DONE, null))
                .isInstanceOf(DeliveryNotFoundException.class);
    }

    @Test
    void retryReopensFailedActionsAndDelivery() {
        when(deliveryRepository.findByIdAndStoreId(DELIVERY_ID, STORE_ID))
                .thenReturn(Optional.of(delivery(PlanDeliveryStatus.FAILED)));
        when(execRepository.findByDeliveryIdOrderBySeqForUpdate(DELIVERY_ID)).thenReturn(
                List.of(exec(0, DeliveryActionStatus.FAILED), exec(1, DeliveryActionStatus.DONE)));
        when(execRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        PlanDelivery retried = service.retry(MERCHANT_ID, DELIVERY_ID);

        assertThat(retried.status()).isEqualTo(PlanDeliveryStatus.PENDING);
        verify(execRepository).saveAll(org.mockito.ArgumentMatchers.argThat(
                (org.mockito.ArgumentMatcher<List<DeliveryActionExec>>) list ->
                        list.stream().anyMatch(e -> e.status() == DeliveryActionStatus.PENDING)));
    }

    @Test
    void retryOfPendingDeliveryIsIdempotent() {
        PlanDelivery pending = delivery(PlanDeliveryStatus.PENDING);
        when(deliveryRepository.findByIdAndStoreId(DELIVERY_ID, STORE_ID)).thenReturn(Optional.of(pending));

        PlanDelivery result = service.retry(MERCHANT_ID, DELIVERY_ID);

        assertThat(result.status()).isEqualTo(PlanDeliveryStatus.PENDING);
        verify(execRepository, never()).saveAll(any());
        verify(deliveryRepository, never()).save(any());
    }

    private PlanDelivery argThat(java.util.function.Predicate<PlanDelivery> predicate) {
        return org.mockito.ArgumentMatchers.argThat(
                (org.mockito.ArgumentMatcher<PlanDelivery>) predicate::test);
    }
}
