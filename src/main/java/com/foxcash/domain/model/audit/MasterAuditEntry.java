package com.foxcash.domain.model.audit;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entrada imutavel da trilha de auditoria do master (Spec 37).
 *
 * <p>Registra UMA acao administrativa na mesma transacao da acao: quem
 * ({@code actorId} = master.id() do principal), o que ({@code action}), o alvo
 * ({@code merchantId} + {@code referenceType}/{@code referenceId}), o estado
 * anterior/posterior ({@code beforeJson}/{@code afterJson} — dados sensiveis
 * mascarados, nunca em claro), o {@code reason} (obrigatorio em acoes que
 * alteram dinheiro) e o {@code ip}. Append-only: nenhum update/delete.
 */
public record MasterAuditEntry(
        UUID id,
        UUID actorId,
        MasterAuditAction action,
        UUID merchantId,
        String referenceType,
        UUID referenceId,
        String beforeJson,
        String afterJson,
        String reason,
        String ip,
        Instant createdAt) {

    public MasterAuditEntry {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    /** Cria uma entrada da trilha (usada pelo recorder). */
    public static MasterAuditEntry record(UUID actorId, MasterAuditAction action,
                                          UUID merchantId, String referenceType, UUID referenceId,
                                          String beforeJson, String afterJson,
                                          String reason, String ip, Instant now) {
        return new MasterAuditEntry(UUID.randomUUID(), actorId, action, merchantId,
                referenceType, referenceId, beforeJson, afterJson, reason, ip, now);
    }
}
