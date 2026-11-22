package com.foxcash.domain.port.out;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.merchant.Merchant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saida: persistencia de lojistas.
 */
public interface MerchantRepository {

    Merchant save(Merchant merchant);

    Optional<Merchant> findById(UUID merchantId);

    /**
     * Lê o lojista com lock pessimista de escrita (SELECT ... FOR UPDATE).
     * Usado nos fluxos de dinheiro (webhook/estorno/saque) para serializar a
     * atualização do wallet e evitar perda de atualização/crédito duplo.
     */
    Optional<Merchant> findByIdForUpdate(UUID merchantId);

    /** Todos os lojistas com status ACTIVE (usado pelo scheduler de faturas). */
    List<Merchant> findAllActive();

    /** Todos os lojistas, independente do status (monitoramento de risco, Spec 16). */
    List<Merchant> findAll();

    /**
     * Página de lojistas (auditoria 2026-08-05/M-A3): para jobs de varredura
     * (reconciliação/risco) sem carregar a tabela inteira em memória.
     */
    PageResult<Merchant> findAll(int page, int size);

    /**
     * Busca por e-mail (Spec 39) — identificador único de login, normalizado em
     * minúsculas (o chamador normaliza; o índice é sobre {@code lower(email)}).
     */
    Optional<Merchant> findByEmail(String email);

    /**
     * Busca pelo hash SHA-256 da chave de API (Spec 12). O texto claro da chave
     * nunca e persistido — a autenticacao calcula o hash do header {@code X-Api-Key}
     * e busca por ele na coluna indexada.
     */
    Optional<Merchant> findByApiKeyHash(String apiKeyHash);

    /**
     * Limpa o hash da chave legada (Spec 12, {@code merchants.api_key_hash})
     * quando casa com {@code keyHash} — SEC-24 (revisão de segurança
     * 2026-08-11): a revogação da chave gerenciável (Spec 24) também revoga a
     * credencial do cadastro; sem isso o fallback legado do ApiKeyAuthFilter
     * (escopo total implícito) sobreviveria à revogação.
     */
    void clearLegacyApiKeyHashIfMatches(UUID merchantId, String keyHash);
}
