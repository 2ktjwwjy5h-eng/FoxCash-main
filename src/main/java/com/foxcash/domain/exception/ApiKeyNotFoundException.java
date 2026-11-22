package com.foxcash.domain.exception;

import java.util.UUID;

/**
 * Chave de API inexistente ou de outro lojista (Spec 24) — mapeada para 404
 * {@code api_key_not_found} (mesmo padrão do GiftPreset: não revela existência).
 */
public class ApiKeyNotFoundException extends RuntimeException {

    public ApiKeyNotFoundException(UUID keyId) {
        super("Chave de API não encontrada: " + keyId);
    }
}
