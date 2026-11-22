package com.foxcash.application.service;

import com.foxcash.domain.exception.DeliveryActionNotFoundException;
import com.foxcash.domain.exception.DeliveryNotFoundException;
import com.foxcash.domain.exception.StoreNotFoundException;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.delivery.DeliveryActionExec;
import com.foxcash.domain.model.delivery.DeliveryActionStatus;
import com.foxcash.domain.model.delivery.PlanDelivery;
import com.foxcash.domain.model.delivery.PlanDeliveryStatus;
import com.foxcash.domain.port.in.DeliveryActionCompletionUseCase;
import com.foxcash.domain.port.out.PlanDeliveryActionExecRepository;
import com.foxcash.domain.port.out.PlanDeliveryRepository;
import com.foxcash.domain.port.out.StoreRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aplicação: execução das ações de entrega (Spec 49) — o bot confirma cada
 * ação (DONE/FAILED); ao completar, a entrega é avaliada: todas DONE →
 * DELIVERED (automático); qualquer FAILED → FAILED. Retry reabre as ações
 * FAILED como PENDING e a entrega volta a PENDING. Replay idempotente (204).
 * Auditoria {@code DELIVERY_ACTION_COMPLETED}/{@code DELIVERY_COMPLETED}.
 */
@Service
public class DeliveryActionCompletionService implements DeliveryActionCompletionUseCase {

    private final StoreRepository storeRepository;
    private final PlanDeliveryRepository deliveryRepository;
    private final PlanDeliveryActionExecRepository execRepository;
    private final PlatformActivityRecorder activityRecorder;
    private final Clock clock;

    public DeliveryActionCompletionService(StoreRepository storeRepository,
                                           PlanDeliveryRepository deliveryRepository,
                                           PlanDeliveryActionExecRepository execRepository,
                                           PlatformActivityRecorder activityRecorder,
                                           Clock clock) {
        this.storeRepository = storeRepository;
        this.deliveryRepository = deliveryRepository;
        this.execRepository = execRepository;
        this.activityRecorder = activityRecorder;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void complete(UUID merchantId, UUID deliveryId, int seq,
                         DeliveryActionStatus status, String note) {
        UUID storeId = requireStore(merchantId);
        PlanDelivery delivery = deliveryRepository.findByIdAndStoreId(deliveryId, storeId)
                .orElseThrow(DeliveryNotFoundException::new);
        // Lock pessimista (SELECT ... FOR UPDATE): serializa conclusões
        // CONCORRENTES da mesma entrega — replay não re-executa a ação (1
        // execução, 1 auditoria). Bug exposto pelo E2E concurrency.spec.js.
        List<DeliveryActionExec> execs = execRepository.findByDeliveryIdOrderBySeqForUpdate(deliveryId);
        DeliveryActionExec exec = execs.stream()
                .filter(a -> a.seq() == seq)
                .findFirst()
                .orElseThrow(DeliveryActionNotFoundException::new);

        if (!exec.complete(status, note, clock.instant())) {
            return; // replay — ação já concluída (204 idempotente)
        }
        execRepository.save(exec);

        if (delivery.evaluate(execs, clock.instant())) {
            deliveryRepository.save(delivery);
        }
        activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                PlatformAction.DELIVERY_ACTION_COMPLETED, "DELIVERY", deliveryId, merchantId,
                Map.of("seq", seq, "status", status.name()));
        if (delivery.status() == PlanDeliveryStatus.DELIVERED) {
            activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                    PlatformAction.DELIVERY_COMPLETED, "DELIVERY", deliveryId, merchantId,
                    Map.of("transactionId", delivery.transactionId()));
        }
    }

    @Override
    @Transactional
    public PlanDelivery retry(UUID merchantId, UUID deliveryId) {
        UUID storeId = requireStore(merchantId);
        PlanDelivery delivery = deliveryRepository.findByIdAndStoreId(deliveryId, storeId)
                .orElseThrow(DeliveryNotFoundException::new);
        if (delivery.status() == PlanDeliveryStatus.PENDING) {
            return delivery; // idempotente — nada a reabrir
        }
        // Lock pessimista (mesmo critério do complete) — retry concorrente não duplica.
        List<DeliveryActionExec> execs = execRepository.findByDeliveryIdOrderBySeqForUpdate(deliveryId);
        boolean anyReopened = false;
        for (DeliveryActionExec exec : execs) {
            anyReopened |= exec.reopenForRetry();
        }
        if (anyReopened) {
            execRepository.saveAll(execs);
        }
        if (delivery.reopenForRetry()) {
            deliveryRepository.save(delivery);
        }
        return delivery;
    }

    private UUID requireStore(UUID merchantId) {
        return storeRepository.findByMerchantId(merchantId)
                .orElseThrow(StoreNotFoundException::new)
                .id();
    }
}
