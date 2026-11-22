package com.foxcash.infrastructure.inbound.rest.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Atualizacao de webhook de saida (Spec 35): campos opcionais — somente o que
 * vier preenchido e alterado. A URL e validada contra SSRF no servico.
 * Spec 148: {@code signatureVersion} (v2 default | v1 legado) opcional.
 */
public record WebhookUpdateRequest(@Size(max = 500) String url, List<String> events,
                                   @Size(max = 10) String signatureVersion) {
}
