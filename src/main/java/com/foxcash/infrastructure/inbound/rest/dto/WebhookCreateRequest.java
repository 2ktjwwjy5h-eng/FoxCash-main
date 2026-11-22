package com.foxcash.infrastructure.inbound.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Criacao de webhook de saida (Spec 35): URL de destino + eventos assinados.
 * O segredo e gerado pelo servidor e devolvido uma unica vez no 201. A URL e
 * validada contra SSRF no servico (400 {@code invalid_argument}).
 */
public record WebhookCreateRequest(@NotBlank @Size(max = 500) String url, List<String> events) {
}
