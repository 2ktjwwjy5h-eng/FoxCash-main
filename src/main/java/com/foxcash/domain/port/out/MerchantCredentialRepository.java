package com.foxcash.domain.port.out;

import com.foxcash.domain.model.merchant.MerchantCredential;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída: persistência de credenciais do portal do lojista (Spec 20).
 */
public interface MerchantCredentialRepository {

    MerchantCredential save(MerchantCredential credential);

    Optional<MerchantCredential> findByMerchantId(UUID merchantId);

    /** Leitura com lock pessimista de escrita (SEC-01): serializa o incremento
     * do contador de falhas de login entre requisições concorrentes — sem
     * lost update no estado de lockout. */
    Optional<MerchantCredential> findByMerchantIdForUpdate(UUID merchantId);
}
