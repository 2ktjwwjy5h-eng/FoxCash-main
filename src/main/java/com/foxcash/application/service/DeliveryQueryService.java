package com.foxcash.application.service;

import com.foxcash.domain.exception.DeliveryNotFoundException;
import com.foxcash.domain.exception.StoreNotFoundException;
import com.foxcash.domain.model.delivery.DeliveryAccessLog;
import com.foxcash.domain.model.delivery.DeliveryActionExec;
import com.foxcash.domain.model.delivery.PlanDelivery;
import com.foxcash.domain.port.in.DeliveryQueryUseCase;
import com.foxcash.domain.port.out.DeliveryAccessLogRepository;
import com.foxcash.domain.port.out.PlanDeliveryActionExecRepository;
import com.foxcash.domain.port.out.PlanDeliveryRepository;
import com.foxcash.domain.port.out.StoreRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Aplicação: consulta de entregas (Spec 49) — o bot (API key do lojista) busca
 * as pendências e recebe o destinatário ({@code platformUserId}) + as ações
 * com payload **em claro apenas ao dono** (criptografados em repouso,
 * decifrados na leitura). 404 cross-loja sem revelar existência.
 */
@Service
public class DeliveryQueryService implements DeliveryQueryUseCase {

    private final StoreRepository storeRepository;
    private final PlanDeliveryRepository deliveryRepository;
    private final PlanDeliveryActionExecRepository execRepository;
    private final DeliveryAccessLogRepository accessLogRepository;

    public DeliveryQueryService(StoreRepository storeRepository,
                                PlanDeliveryRepository deliveryRepository,
                                PlanDeliveryActionExecRepository execRepository,
                                DeliveryAccessLogRepository accessLogRepository) {
        this.storeRepository = storeRepository;
        this.deliveryRepository = deliveryRepository;
        this.execRepository = execRepository;
        this.accessLogRepository = accessLogRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PlanDelivery> pending(UUID merchantId) {
        return deliveryRepository.findByStoreIdAndStatusPending(requireStore(merchantId));
    }

    @Override
    @Transactional(readOnly = true)
    public PlanDelivery byTransaction(UUID merchantId, UUID transactionId) {
        UUID storeId = requireStore(merchantId);
        PlanDelivery delivery = deliveryRepository.findByTransactionId(transactionId)
                .orElseThrow(DeliveryNotFoundException::new);
        if (!delivery.storeId().equals(storeId)) {
            throw new DeliveryNotFoundException();
        }
        return delivery;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeliveryActionExec> actions(UUID merchantId, UUID deliveryId) {
        requireDelivery(merchantId, deliveryId);
        return execRepository.findByDeliveryIdOrderBySeq(deliveryId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeliveryAccessLog> accessLogs(UUID merchantId, UUID deliveryId) {
        requireDelivery(merchantId, deliveryId);
        return accessLogRepository.findByDeliveryIdOrderByAccessedAt(deliveryId);
    }

    private UUID requireStore(UUID merchantId) {
        return storeRepository.findByMerchantId(merchantId)
                .orElseThrow(StoreNotFoundException::new)
                .id();
    }

    private PlanDelivery requireDelivery(UUID merchantId, UUID deliveryId) {
        return deliveryRepository.findByIdAndStoreId(deliveryId, requireStore(merchantId))
                .orElseThrow(DeliveryNotFoundException::new);
    }
}
