package com.foxcash.infrastructure.inbound.rest.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request de criacao de chave de API do portal (Spec 24): label + escopos.
 * Escopos vazios/ausentes = escopo total (padrao da chave do cadastro);
 * escopo desconhecido → 400 {@code invalid_argument}. Auditoria 2026-08-05
 * (M2): limite de label alinhado a regra do servico (max 80).
 */
public record CreateApiKeyRequest(@Size(max = 80) String label, List<String> scopes) {
}
