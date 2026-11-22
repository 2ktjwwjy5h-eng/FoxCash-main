package com.foxcash.infrastructure.inbound.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Teste de entrega de webhook (Spec 35): envia um ping assinado para a URL
 * informada — nao persiste nada. A URL e validada contra SSRF no servico.
 */
public record WebhookTestRequest(@NotBlank @Size(max = 500) String url,
                                 @NotBlank String secret,
                                 String eventType) {
}
