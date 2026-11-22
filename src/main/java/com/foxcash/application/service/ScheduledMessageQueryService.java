package com.foxcash.application.service;

import com.foxcash.domain.exception.StoreMessageNotFoundException;
import com.foxcash.domain.exception.StoreNotFoundException;
import com.foxcash.domain.model.store.ScheduledMessage;
import com.foxcash.domain.model.store.ScheduledMessageStatus;
import com.foxcash.domain.port.in.ScheduledMessageQueryUseCase;
import com.foxcash.domain.port.out.PlanDeliveryRepository;
import com.foxcash.domain.port.out.ScheduledMessageRepository;
import com.foxcash.domain.port.out.StoreRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Aplicação: consulta das mensagens programadas (Spec 51) — o bot (API key do
 * lojista) busca as vencidas com os {@code recipients} (platform_user_id dos
 * compradores com entrega entregue da loja — Spec 49, deduplicados; em claro
 * ao dono, criptografados em repouso) e confirma o envio (SENT, idempotente).
 * 404 cross-loja sem revelar existência.
 */
@Service
public class ScheduledMessageQueryService implements ScheduledMessageQueryUseCase {

    private final StoreRepository storeRepository;
    private final ScheduledMessageRepository scheduledMessageRepository;
    private final PlanDeliveryRepository deliveryRepository;
    private final Clock clock;

    public ScheduledMessageQueryService(StoreRepository storeRepository,
                                        ScheduledMessageRepository scheduledMessageRepository,
                                        PlanDeliveryRepository deliveryRepository,
                                        Clock clock) {
        this.storeRepository = storeRepository;
        this.scheduledMessageRepository = scheduledMessageRepository;
        this.deliveryRepository = deliveryRepository;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DueScheduledMessage> due(UUID merchantId, Instant dueBefore) {
        UUID storeId = requireStore(merchantId);
        List<String> recipients = deliveryRepository.findDistinctDeliveredPlatformUserIds(storeId);
        return scheduledMessageRepository.findByStoreIdAndStatus(storeId, ScheduledMessageStatus.SCHEDULED)
                .stream()
                .filter(message -> message.isDue(dueBefore))
                .map(message -> new DueScheduledMessage(message, recipients))
                .toList();
    }

    @Override
    @Transactional
    public void markSent(UUID merchantId, UUID scheduledMessageId) {
        UUID storeId = requireStore(merchantId);
        ScheduledMessage message = scheduledMessageRepository
                .findByIdAndStoreId(scheduledMessageId, storeId)
                .orElseThrow(StoreMessageNotFoundException::new);
        // Idempotente: replay de um envio já confirmado vira 204 no-op.
        message.markSent(clock.instant());
        scheduledMessageRepository.save(message);
    }

    private UUID requireStore(UUID merchantId) {
        return storeRepository.findByMerchantId(merchantId)
                .orElseThrow(StoreNotFoundException::new)
                .id();
    }
}
