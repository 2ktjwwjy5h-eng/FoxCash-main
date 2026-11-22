package com.foxcash.application.service;

import com.foxcash.domain.exception.StoreMessageNotFoundException;
import com.foxcash.domain.exception.StoreNotFoundException;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.store.ScheduledMessage;
import com.foxcash.domain.model.store.StoreMessage;
import com.foxcash.domain.port.in.StoreMessageManagementUseCase;
import com.foxcash.domain.port.out.ScheduledMessageRepository;
import com.foxcash.domain.port.out.StoreMessageRepository;
import com.foxcash.domain.port.out.StoreRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aplicação: gestão das mensagens da loja pelo portal (Spec 51) — o vendedor
 * edita os textos (upsert por {@code event}: 1 por etapa) e agenda envios em
 * massa; o bot apenas renderiza (Spec 52). Tudo por {@code merchantId} do
 * principal; a loja (1:1) é resolvida internamente (404 cross-loja sem revelar
 * existência). Auditoria {@code MESSAGE_CREATED/UPDATED/DELETED/SCHEDULED}
 * (Spec 41) na mesma transação — o {@code text} (pode conter link privado)
 * nunca vai em claro para a trilha (registra apenas o {@code event}/tipo).
 */
@Service
public class StoreMessageService implements StoreMessageManagementUseCase {

    private final StoreRepository storeRepository;
    private final StoreMessageRepository messageRepository;
    private final ScheduledMessageRepository scheduledMessageRepository;
    private final PlatformActivityRecorder activityRecorder;
    private final Clock clock;

    public StoreMessageService(StoreRepository storeRepository,
                               StoreMessageRepository messageRepository,
                               ScheduledMessageRepository scheduledMessageRepository,
                               PlatformActivityRecorder activityRecorder,
                               Clock clock) {
        this.storeRepository = storeRepository;
        this.messageRepository = messageRepository;
        this.scheduledMessageRepository = scheduledMessageRepository;
        this.activityRecorder = activityRecorder;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoreMessage> list(UUID merchantId) {
        return messageRepository.findByStoreId(requireStore(merchantId));
    }

    @Override
    @Transactional(readOnly = true)
    public StoreMessage get(UUID merchantId, UUID messageId) {
        return messageRepository.findByIdAndStoreId(messageId, requireStore(merchantId))
                .orElseThrow(StoreMessageNotFoundException::new);
    }

    @Override
    @Transactional
    public UpsertResult upsert(UUID merchantId, SaveMessageCommand command) {
        if (command.event() == null) {
            throw new IllegalArgumentException("event e obrigatorio na mensagem da loja");
        }
        UUID storeId = requireStore(merchantId);
        Instant now = clock.instant();
        StoreMessage existing = messageRepository.findByStoreIdAndEvent(storeId, command.event())
                .orElse(null);
        if (existing != null) {
            existing.update(command.text(), command.active(), now);
            StoreMessage saved = messageRepository.save(existing);
            activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                    PlatformAction.MESSAGE_UPDATED, "STORE_MESSAGE", saved.id(), merchantId,
                    Map.of("event", saved.event()));
            return new UpsertResult(saved, false);
        }
        StoreMessage created = messageRepository.save(
                StoreMessage.create(storeId, command.event(), requireText(command.text()), now));
        activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                PlatformAction.MESSAGE_CREATED, "STORE_MESSAGE", created.id(), merchantId,
                Map.of("event", created.event()));
        return new UpsertResult(created, true);
    }

    @Override
    @Transactional
    public StoreMessage update(UUID merchantId, UUID messageId, SaveMessageCommand command) {
        UUID storeId = requireStore(merchantId);
        StoreMessage message = messageRepository.findByIdAndStoreId(messageId, storeId)
                .orElseThrow(StoreMessageNotFoundException::new);
        message.update(command.text(), command.active(), clock.instant());
        StoreMessage saved = messageRepository.save(message);
        activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                PlatformAction.MESSAGE_UPDATED, "STORE_MESSAGE", saved.id(), merchantId,
                Map.of("event", saved.event()));
        return saved;
    }

    @Override
    @Transactional
    public void delete(UUID merchantId, UUID messageId) {
        UUID storeId = requireStore(merchantId);
        StoreMessage message = messageRepository.findByIdAndStoreId(messageId, storeId)
                .orElseThrow(StoreMessageNotFoundException::new);
        messageRepository.delete(message);
        activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                PlatformAction.MESSAGE_DELETED, "STORE_MESSAGE", messageId, merchantId,
                Map.of("event", message.event()));
    }

    @Override
    @Transactional
    public ScheduledMessage schedule(UUID merchantId, ScheduleMessageCommand command) {
        UUID storeId = requireStore(merchantId);
        ScheduledMessage message = ScheduledMessage.create(storeId, requireText(command.text()),
                command.scheduledAt(), command.cron(), command.audience(), clock.instant());
        ScheduledMessage saved = scheduledMessageRepository.save(message);
        activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                PlatformAction.MESSAGE_SCHEDULED, "SCHEDULED_MESSAGE", saved.id(), merchantId,
                Map.of("audience", saved.audience(),
                        "kind", saved.cron() == null ? "one-shot" : "cron"));
        return saved;
    }

    private UUID requireStore(UUID merchantId) {
        return storeRepository.findByMerchantId(merchantId)
                .orElseThrow(StoreNotFoundException::new)
                .id();
    }

    private String requireText(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text e obrigatorio na mensagem da loja");
        }
        return text;
    }
}
