package com.foxcash.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.domain.model.plan.DeliveryActionType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validação/serialização do payload das ações de entrega (Spec 49).
 *
 * <p>Cada {@link DeliveryActionType} exige um conjunto de chaves (ex.:
 * {@code grant_role} → {@code roleId}; {@code send_link} → {@code url} e
 * {@code label} opcional). O payload é serializado em JSON (string) para ser
 * criptografado em repouso pela {@code CryptoConverter}.
 */
@Component
public class PlanActionPayloadCodec {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public PlanActionPayloadCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Valida as chaves obrigatórias do tipo e serializa em JSON. */
    public String validateAndEncode(DeliveryActionType type, Map<String, Object> payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload e obrigatorio na acao de entrega");
        }
        String requiredKey = switch (type) {
            case GRANT_ROLE -> "roleId";
            case SEND_MESSAGE -> "text";
            case SEND_LINK -> "url";
            case ADD_TO_GROUP -> "groupId";
            case SEND_FILE -> "fileUrl";
        };
        Object required = payload.get(requiredKey);
        if (required == null || required.toString().isBlank()) {
            throw new IllegalArgumentException(
                    "payload de " + type + " exige a chave \"" + requiredKey + "\"");
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("payload invalido: " + e.getMessage());
        }
    }

    /** Lê o JSON (já decifrado na persistência) de volta em Map — para resposta. */
    public Map<String, Object> decode(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> value = objectMapper.readValue(payloadJson, MAP_TYPE);
            return value == null ? new LinkedHashMap<>() : value;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao ler payload da acao de entrega", e);
        }
    }
}
