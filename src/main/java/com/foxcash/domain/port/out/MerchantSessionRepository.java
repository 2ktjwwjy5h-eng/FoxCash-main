package com.foxcash.domain.port.out;

import com.foxcash.domain.model.merchant.MerchantSession;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída: persistência de sessões do portal do lojista (Spec 20).
 */
public interface MerchantSessionRepository {

    MerchantSession save(MerchantSession session);

    Optional<MerchantSession> findByTokenHash(String tokenHash);

    /**
     * Revoga (marca {@code revoked_at}) todas as sessões **ativas** do lojista —
     * usado no reset de senha (Spec 40): a troca de senha invalida as sessões
     * existentes.
     */
    void revokeAllByMerchantId(UUID merchantId, Instant now);
}
