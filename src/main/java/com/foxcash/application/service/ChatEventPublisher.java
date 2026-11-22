package com.foxcash.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.domain.model.crm.ChatMessage;
import com.foxcash.infrastructure.config.CrmProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Push SSE + fan-out Redis do chat do CRM (Spec 83 D8) — entrega imediata aos
 * {@code SseEmitter} locais da instância (single-instance/dev/E2E) e publica no
 * Redis pub/sub ({@code foxcash:crm:events:{merchantId}}) para as demais réplicas
 * encaminharem aos SSEs locais delas. <b>Fail-open (ADR-010)</b>: com Redis
 * indisponível a entrega local continua funcionando (best-effort com try/catch).
 *
 * <p>Buffer curto por lojista para o {@code Last-Event-ID} (reconexão —
 * {@code CrmProperties.sseBufferSize}); o cliente dedup por {@code messageId}
 * (reentrega é aceitável — at-least-once, Spec 83 D7).
 */
@Component
public class ChatEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ChatEventPublisher.class);

    /** Evento push do chat: {@code type} (message) + leadId + mensagem. */
    public record ChatEvent(String type, UUID leadId, ChatMessage message) {
    }

    private final CrmProperties properties;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;
    private final Map<UUID, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final Map<UUID, ArrayDeque<ChatEvent>> buffers = new ConcurrentHashMap<>();

    public ChatEventPublisher(CrmProperties properties, ObjectMapper objectMapper,
                              StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
    }

    /** Abre uma conexão SSE para o lojista (retoma do buffer se {@code lastEventId}). */
    public SseEmitter connect(UUID merchantId, String lastEventId) {
        SseEmitter emitter = new SseEmitter(0L); // sem timeout (heartbeat da app mantém vivo)
        emitters.computeIfAbsent(merchantId, k -> ConcurrentHashMap.newKeySet()).add(emitter);
        meterRegistry.counter("foxcash_crm_sse_connected_total").increment();
        replayBuffer(merchantId, lastEventId, emitter);
        emitter.onCompletion(() -> remove(merchantId, emitter));
        emitter.onTimeout(() -> remove(merchantId, emitter));
        emitter.onError(e -> remove(merchantId, emitter));
        return emitter;
    }

    /** Publica o evento: entrega local + Redis (best-effort, fail-open). */
    public void publish(UUID merchantId, ChatEvent event) {
        buffers.computeIfAbsent(merchantId, k -> new ArrayDeque<>()).addLast(event);
        ArrayDeque<ChatEvent> buffer = buffers.get(merchantId);
        while (buffer.size() > properties.getSseBufferSize()) {
            buffer.removeFirst();
        }
        deliver(merchantId, event);
        if (properties.isRedisEnabled()) {
            try {
                redisTemplate.convertAndSend(channel(merchantId), objectMapper.writeValueAsString(event));
            } catch (Exception e) {
                log.warn("CRM: Redis indisponivel (fail-open) — entrega local apenas: {}", e.getMessage());
            }
        }
    }

    /** Recebe evento do Redis (outra instância) e entrega aos SSEs locais. */
    public void onRedisEvent(String channel, String body) {
        UUID merchantId = merchantFromChannel(channel);
        if (merchantId == null || body == null || body.isBlank()) {
            return;
        }
        try {
            deliver(merchantId, objectMapper.readValue(body, ChatEvent.class));
        } catch (Exception e) {
            log.warn("CRM: evento Redis invalido no canal {}: {}", channel, e.getMessage());
        }
    }

    private void deliver(UUID merchantId, ChatEvent event) {
        Set<SseEmitter> set = emitters.get(merchantId);
        if (set == null || set.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : set) {
            try {
                emitter.send(SseEmitter.event()
                        .id(event.message().id().toString())
                        .name(event.type())
                        .data(event));
            } catch (Exception e) {
                remove(merchantId, emitter);
            }
        }
    }

    private void replayBuffer(UUID merchantId, String lastEventId, SseEmitter emitter) {
        ArrayDeque<ChatEvent> buffer = buffers.get(merchantId);
        if (buffer == null || buffer.isEmpty()) {
            return;
        }
        for (ChatEvent event : buffer) {
            if (lastEventId == null || lastEventId.isBlank()
                    || !lastEventId.equals(event.message().id().toString())) {
                try {
                    emitter.send(SseEmitter.event()
                            .id(event.message().id().toString())
                            .name(event.type())
                            .data(event));
                } catch (Exception e) {
                    remove(merchantId, emitter);
                    return;
                }
            }
        }
    }

    private void remove(UUID merchantId, SseEmitter emitter) {
        Set<SseEmitter> set = emitters.get(merchantId);
        if (set != null) {
            set.remove(emitter);
        }
    }

    private String channel(UUID merchantId) {
        return properties.getChatEventChannelPrefix() + merchantId;
    }

    private UUID merchantFromChannel(String channel) {
        String prefix = properties.getChatEventChannelPrefix();
        if (channel == null || !channel.startsWith(prefix)) {
            return null;
        }
        try {
            return UUID.fromString(channel.substring(prefix.length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
