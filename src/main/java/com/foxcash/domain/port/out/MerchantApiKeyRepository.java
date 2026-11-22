package com.foxcash.domain.port.out;

import com.foxcash.domain.model.apikey.MerchantApiKey;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída: persistência de chaves de API gerenciáveis do lojista
 * (Spec 24). Apenas o hash SHA-256 e o prefixo são persistidos — o segredo em
 * claro nunca.
 */
public interface MerchantApiKeyRepository {

    MerchantApiKey save(MerchantApiKey key);

    /** Busca pelo hash SHA-256 — usada pelo filtro de autenticação (Spec 12). */
    Optional<MerchantApiKey> findByKeyHash(String keyHash);

    /** Chaves do lojista (mais recentes primeiro). */
    List<MerchantApiKey> findByMerchantId(UUID merchantId);

    /** Chave do lojista por id — vazio se inexistente OU de outro lojista. */
    Optional<MerchantApiKey> findByIdAndMerchantId(UUID id, UUID merchantId);

    /** Total de chaves ativas do lojista (limite do portal). */
    long countActiveByMerchantId(UUID merchantId);

    /** Atualiza o último uso sem recarregar a entidade (chamado no filtro). */
    void recordUse(UUID keyId, Instant usedAt);
}
