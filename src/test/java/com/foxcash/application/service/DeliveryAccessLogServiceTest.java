package com.foxcash.application.service;

import com.foxcash.domain.exception.DeliveryNotFoundException;
import com.foxcash.domain.exception.StoreNotFoundException;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.delivery.DeliveryAccessLog;
import com.foxcash.domain.model.delivery.PlanDelivery;
import com.foxcash.domain.model.delivery.PlanDeliveryStatus;
import com.foxcash.domain.model.plan.Platform;
import com.foxcash.domain.model.store.Store;
import com.foxcash.domain.port.out.DeliveryAccessLogRepository;
import com.foxcash.domain.port.out.PlanDeliveryRepository;
import com.foxcash.domain.port.out.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do registro de acesso ao conteúdo entregue (Spec 49+): grava o log
 * com IP/dispositivo do request, auditoria {@code DELIVERY_ACCESS_RECORDED} e
 * 404 cross-loja (não revela existência).
 */
class DeliveryAccessLogServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID DELIVERY_ID = UUID.randomUUID();
    private static final UUID TX_ID = UUID.randomUUID();

    private final StoreRepository storeRepository = mock(StoreRepository.class);
    private final PlanDeliveryRepository deliveryRepository = mock(PlanDeliveryRepository.class);
    private final DeliveryAccessLogRepository accessLogRepository = mock(DeliveryAccessLogRepository.class);
    private final PlatformActivityRecorder activityRecorder = mock(PlatformActivityRecorder.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private DeliveryAccessLogService service;

    @BeforeEach
    void setUp() {
        service = new DeliveryAccessLogService(storeRepository, deliveryRepository,
                accessLogRepository, activityRecorder, clock);
        when(storeRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(Optional.of(Store.restore(STORE_ID, MERCHANT_ID, "Loja", null, null,
                        null, List.of(), null, null, null, null, NOW, NOW)));
    }

    private PlanDelivery delivery() {
        return PlanDelivery.restore(DELIVERY_ID, STORE_ID, UUID.randomUUID(), TX_ID,
                Platform.DISCORD, "user-1", "buyer-hash", PlanDeliveryStatus.PENDING, NOW, null);
    }

    @Test
    void recordSavesLogWithIpDeviceAndNote() {
        when(deliveryRepository.findByIdAndStoreId(DELIVERY_ID, STORE_ID))
                .thenReturn(Optional.of(delivery()));

        service.record(MERCHANT_ID, DELIVERY_ID, "200.201.202.203", "FoxBot/1.0", "acessou o link");

        verify(accessLogRepository).save(any(DeliveryAccessLog.class));
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.DELIVERY_ACCESS_RECORDED), eq("DELIVERY"), eq(DELIVERY_ID),
                eq(MERCHANT_ID), any(Map.class));
    }

    @Test
    void recordAllowsNullIpDeviceAndNote() {
        when(deliveryRepository.findByIdAndStoreId(DELIVERY_ID, STORE_ID))
                .thenReturn(Optional.of(delivery()));

        service.record(MERCHANT_ID, DELIVERY_ID, null, null, null);

        verify(accessLogRepository).save(any(DeliveryAccessLog.class));
    }

    @Test
    void recordThrowsDeliveryNotFoundForCrossStore() {
        when(deliveryRepository.findByIdAndStoreId(DELIVERY_ID, STORE_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.record(MERCHANT_ID, DELIVERY_ID, "1.2.3.4", null, null))
                .isInstanceOf(DeliveryNotFoundException.class);
        verify(accessLogRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void recordThrowsStoreNotFoundWhenMerchantHasNoStore() {
        when(storeRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.record(MERCHANT_ID, DELIVERY_ID, null, null, null))
                .isInstanceOf(StoreNotFoundException.class);
    }
}
