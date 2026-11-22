package com.foxcash.application.service;

import com.foxcash.domain.exception.DeliveryNotFoundException;
import com.foxcash.domain.exception.StoreNotFoundException;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.delivery.DeliveryAccessLog;
import com.foxcash.domain.model.delivery.PlanDelivery;
import com.foxcash.domain.port.in.DeliveryAccessRecordingUseCase;
import com.foxcash.domain.port.out.DeliveryAccessLogRepository;
import com.foxcash.domain.port.out.PlanDeliveryRepository;
import com.foxcash.domain.port.out.StoreRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * Aplicação: registro de acesso ao conteúdo entregue (Spec 49+, 2026-08-06).
 * O bot informa quando o comprador acessou o produto; o registro vira prova de
 * entrega (evidência em disputa de chargeback). 404 cross-loja sem revelar
 * existência. Auditoria {@code DELIVERY_ACCESS_RECORDED}.
 */
@Service
public class DeliveryAccessLogService implements DeliveryAccessRecordingUseCase {

    private final StoreRepository storeRepository;
    private final PlanDeliveryRepository deliveryRepository;
    private final DeliveryAccessLogRepository accessLogRepository;
    private final PlatformActivityRecorder activityRecorder;
    private final Clock clock;

    public DeliveryAccessLogService(StoreRepository storeRepository,
                                    PlanDeliveryRepository deliveryRepository,
                                    DeliveryAccessLogRepository accessLogRepository,
                                    PlatformActivityRecorder activityRecorder,
                                    Clock clock) {
        this.storeRepository = storeRepository;
        this.deliveryRepository = deliveryRepository;
        this.accessLogRepository = accessLogRepository;
        this.activityRecorder = activityRecorder;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void record(UUID merchantId, UUID deliveryId, String ip, String device, String note) {
        UUID storeId = requireStore(merchantId);
        PlanDelivery delivery = deliveryRepository.findByIdAndStoreId(deliveryId, storeId)
                .orElseThrow(DeliveryNotFoundException::new);
        accessLogRepository.save(DeliveryAccessLog.create(deliveryId, clock.instant(), ip, device, note));
        activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                PlatformAction.DELIVERY_ACCESS_RECORDED, "DELIVERY", deliveryId, merchantId,
                Map.of("transactionId", delivery.transactionId()));
    }

    private UUID requireStore(UUID merchantId) {
        return storeRepository.findByMerchantId(merchantId)
                .orElseThrow(StoreNotFoundException::new)
                .id();
    }
}
