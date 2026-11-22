package com.foxcash.domain.port.in;

import com.foxcash.domain.model.apikey.ApiKeyScope;
import com.foxcash.domain.model.apikey.MerchantApiKey;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Porta de entrada: gestão de chaves de API do lojista pelo portal (Spec 24).
 * O segredo é devolvido uma única vez no {@code create}; as demais operações
 * só expõem prefixo/status/escopos/uso.
 */
public interface ApiKeyManagementUseCase {

    /** Chaves do lojista sem o segredo (prefixo + status + escopos + uso). */
    List<MerchantApiKey> list(UUID merchantId);

    /**
     * Cria uma chave ativa. {@code scopes} vazio = escopo total (os três).
     * Limite de {@value MerchantApiKey#MAX_ACTIVE_KEYS} ativas → 409.
     */
    CreateResult create(UUID merchantId, CreateCommand command);

    /** Revoga uma chave do lojista; inexistente/de outro lojista → 404. */
    void revoke(UUID merchantId, UUID keyId);

    record CreateCommand(String label, Set<ApiKeyScope> scopes) {
    }

    /** Segredo em claro devolvido UMA única vez na criação. */
    record CreateResult(UUID keyId, String secret) {
    }
}
