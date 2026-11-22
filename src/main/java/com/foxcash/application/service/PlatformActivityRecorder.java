package com.foxcash.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.activity.PlatformActivity;
import com.foxcash.domain.port.out.PlatformActivityRepository;
import com.foxcash.infrastructure.security.ClientIpResolver;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Grava um evento da trilha unificada de atividades (Spec 41) — chamado DENTRO
 * do {@code @Transactional} da ação (mesma transação; rollback da ação =
 * rollback do evento).
 *
 * <p>O {@code ip} é resolvido do request atual quando disponível
 * ({@link RequestContextHolder}); em jobs/sistema ({@code SYSTEM}) não há
 * request → {@code ip} fica {@code null}. O {@code details} é serializado em
 * JSON com **máscara de dados sensíveis** (documento, chave Pix, senha, token —
 * nunca em claro).
 */
@Service
public class PlatformActivityRecorder {

    /** Chaves sensiveis que nunca devem aparecer em claro na trilha. */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "document", "documentHash", "destinationKey", "key", "account",
            "agency", "destinationKeySnapshot", "apiKey", "password", "token",
            "portalPassword", "resetToken", "email");

    private final PlatformActivityRepository repository;
    private final ObjectMapper objectMapper;
    private final ClientIpResolver clientIpResolver;

    public PlatformActivityRecorder(PlatformActivityRepository repository,
                                    ObjectMapper objectMapper,
                                    ClientIpResolver clientIpResolver) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clientIpResolver = clientIpResolver;
    }

    /**
     * Registra um evento da plataforma (mesma transação do chamador).
     *
     * @param actorType   MASTER/MERCHANT/SYSTEM
     * @param actorId     master.id()/merchant.id() (null para SYSTEM/jobs)
     * @param action      tipo do evento
     * @param entityType  origem (MERCHANT/TRANSACTION/WITHDRAWAL/INVOICE/SESSION/MASTER...)
     * @param entityId    id da origem (ou null)
     * @param merchantId  lojista afetado (ou null)
     * @param details     contexto da ação (Map/record; serializado com máscara)
     */
    public PlatformActivity record(PlatformActorType actorType, UUID actorId, PlatformAction action,
                                   String entityType, UUID entityId, UUID merchantId, Object details) {
        PlatformActivity activity = PlatformActivity.record(
                actorType, actorId, action, entityType, entityId, merchantId,
                serializeMasked(details), currentIp(), Instant.now());
        return repository.save(activity);
    }

    /** IP do request atual (null fora de request — jobs/sistema). */
    private String currentIp() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servlet && servlet.getRequest() != null) {
            return clientIpResolver.resolve(servlet.getRequest());
        }
        return null;
    }

    private String serializeMasked(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(mask(value));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar details da atividade", e);
        }
    }

    /** Mascara chaves sensiveis (recursivamente) — nunca em claro na trilha. */
    private Object mask(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object v = entry.getValue();
                if (SENSITIVE_KEYS.contains(key)) {
                    result.put(key, "***");
                } else {
                    result.put(key, mask(v));
                }
            }
            return result;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(this::mask).toList();
        }
        return value;
    }
}
