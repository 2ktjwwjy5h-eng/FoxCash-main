package com.foxcash.application.service;

import com.foxcash.domain.exception.DeliveryNotFoundException;
import com.foxcash.domain.exception.StoreNotFoundException;
import com.foxcash.domain.model.delivery.DeliveryAccessLog;
import com.foxcash.domain.model.delivery.DeliveryActionExec;
import com.foxcash.domain.model.delivery.PlanDelivery;
import com.foxcash.domain.model.delivery.PlanDeliveryStatus;
import com.foxcash.domain.model.plan.Platform;
import com.foxcash.domain.model.store.Store;
import com.foxcash.domain.port.out.DeliveryAccessLogRepository;
import com.foxcash.domain.port.out.PlanDeliveryActionExecRepository;
import com.foxcash.domain.port.out.PlanDeliveryRepository;
import com.foxcash.domain.port.out.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
 * Testes do {@link DeliveryQueryService} (Spec 49): consultas por merchantId —
 * 404 cross-loja sem revelar existência; requireStore/requireDelivery validam a
 * posse antes de devolver dados (payload em claro apenas ao dono).
 */
class DeliveryQueryServiceTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID DELIVERY_ID = UUID.randomUUID();
    private static final UUID TX_ID = UUID.randomUUID();

    private final StoreRepository storeRepository = mock(StoreRepository.class);
    private final PlanDeliveryRepository deliveryRepository = mock(PlanDeliveryRepository.class);
    private final PlanDeliveryActionExecRepository execRepository = mock(PlanDeliveryActionExecRepository.class);
    private final DeliveryAccessLogRepository accessLogRepository = mock(DeliveryAccessLogRepository.class);

    private final DeliveryQueryService service = new DeliveryQueryService(
            storeRepository, deliveryRepository, execRepository, accessLogRepository);

    @BeforeEach
    void setUp() {
        when(storeRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(store()));
    }

    private Store store() {
        return Store.restore(STORE_ID, MERCHANT_ID, "Loja A", null, null, null,
                List.of(), null, null, null, null, Instant.now(), Instant.now());
    }

    private PlanDelivery delivery(UUID storeId) {
        return PlanDelivery.restore(DELIVERY_ID, storeId, UUID.randomUUID(), TX_ID,
                Platform.DISCORD, "user-1", null, PlanDeliveryStatus.PENDING, Instant.now(), null);
    }

    @Test
    void pendingReturnsStoreDeliveries() {
        PlanDelivery d = delivery(STORE_ID);
        when(deliveryRepository.findByStoreIdAndStatusPending(STORE_ID)).thenReturn(List.of(d));

        assertThat(service.pending(MERCHANT_ID)).containsExactly(d);
        verify(deliveryRepository).findByStoreIdAndStatusPending(eq(STORE_ID));
    }

    @Test
    void pendingWithoutStoreThrowsNotFound() {
        when(storeRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.pending(MERCHANT_ID))
                .isInstanceOf(StoreNotFoundException.class);
    }

    @Test
    void byTransactionReturnsOwnedDelivery() {
        when(deliveryRepository.findByTransactionId(TX_ID))
                .thenReturn(Optional.of(delivery(STORE_ID)));

        assertThat(service.byTransaction(MERCHANT_ID, TX_ID).id()).isEqualTo(DELIVERY_ID);
    }

    @Test
    void byTransactionCrossStoreIsHiddenAsNotFound() {
        // Entrega de OUTRA loja → 404 (sem revelar existência).
        when(deliveryRepository.findByTransactionId(TX_ID))
                .thenReturn(Optional.of(delivery(UUID.randomUUID())));

        assertThatThrownBy(() -> service.byTransaction(MERCHANT_ID, TX_ID))
                .isInstanceOf(DeliveryNotFoundException.class);
    }

    @Test
    void byTransactionMissingDeliveryThrowsNotFound() {
        when(deliveryRepository.findByTransactionId(TX_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.byTransaction(MERCHANT_ID, TX_ID))
                .isInstanceOf(DeliveryNotFoundException.class);
    }

    @Test
    void actionsRequireOwnershipAndReturnOrdered() {
        when(deliveryRepository.findByIdAndStoreId(DELIVERY_ID, STORE_ID))
                .thenReturn(Optional.of(delivery(STORE_ID)));
        DeliveryActionExec action = mock(DeliveryActionExec.class);
        when(execRepository.findByDeliveryIdOrderBySeq(DELIVERY_ID)).thenReturn(List.of(action));

        assertThat(service.actions(MERCHANT_ID, DELIVERY_ID)).containsExactly(action);
        verify(accessLogRepository, never()).findByDeliveryIdOrderByAccessedAt(any());
    }

    @Test
    void accessLogsRequireOwnershipAndReturnChronological() {
        when(deliveryRepository.findByIdAndStoreId(DELIVERY_ID, STORE_ID))
                .thenReturn(Optional.of(delivery(STORE_ID)));
        DeliveryAccessLog log = mock(DeliveryAccessLog.class);
        when(accessLogRepository.findByDeliveryIdOrderByAccessedAt(DELIVERY_ID)).thenReturn(List.of(log));

        assertThat(service.accessLogs(MERCHANT_ID, DELIVERY_ID)).containsExactly(log);
    }

    @Test
    void actionsForUnknownDeliveryThrowNotFound() {
        when(deliveryRepository.findByIdAndStoreId(DELIVERY_ID, STORE_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.actions(MERCHANT_ID, DELIVERY_ID))
                .isInstanceOf(DeliveryNotFoundException.class);
        verify(execRepository, never()).findByDeliveryIdOrderBySeq(any());
    }
}
