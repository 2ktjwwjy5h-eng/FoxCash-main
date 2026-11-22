package com.foxcash.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.domain.model.audit.MasterAuditAction;
import com.foxcash.domain.model.audit.MasterAuditEntry;
import com.foxcash.domain.port.out.MasterAuditRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Grava uma entrada da trilha de auditoria do master (Spec 37) — chamado DENTRO
 * do {@code @Transactional} da acao administrativa (mesma transacao; rollback da
 * acao = rollback da trilha).
 *
 * <p>{@code actorId} vem do principal ({@code master.id()}, padrão
 * {@code MasterAuthFilter}) — nunca do body. O {@code before}/{@code after} sao
 * serializados em JSON com **mascara de dados sensiveis** (documento, chave Pix,
 * conta, destinationKey nunca em claro).
 */
@Service
public class MasterAuditRecorder {

    /** Chaves sensiveis que nunca devem aparecer em claro no log de auditoria. */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "document", "documentHash", "destinationKey", "key", "account",
            "agency", "destinationKeySnapshot", "apiKey", "password", "token");

    private final MasterAuditRepository repository;
    private final ObjectMapper objectMapper;

    public MasterAuditRecorder(MasterAuditRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Registra uma acao administrativa (mesma transacao do chamador).
     *
     * @param actorId        master.id() do principal (nunca do body)
     * @param action         tipo da acao
     * @param merchantId     lojista alvo (ou null)
     * @param referenceType  origem (WITHDRAWAL/RECEIVABLE/INVOICE/MERCHANT)
     * @param referenceId    id da origem (ou null)
     * @param before         estado anterior (Map com valores; serializado com mascara)
     * @param after          estado posterior (idem)
     * @param reason         motivo (obrigatorio em acoes que alteram dinheiro)
     * @param ip             endereco de origem da requisicao
     */
    public MasterAuditEntry record(UUID actorId, MasterAuditAction action, UUID merchantId,
                                   String referenceType, UUID referenceId,
                                   Object before, Object after, String reason, String ip) {
        MasterAuditEntry entry = MasterAuditEntry.record(
                actorId, action, merchantId, referenceType, referenceId,
                serializeMasked(before), serializeMasked(after), reason, ip, Instant.now());
        return repository.save(entry);
    }

    private String serializeMasked(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(mask(value));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar before/after da auditoria", e);
        }
    }

    /** Mascara chaves sensiveis (recursivamente) — nunca em claro no log. */
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
