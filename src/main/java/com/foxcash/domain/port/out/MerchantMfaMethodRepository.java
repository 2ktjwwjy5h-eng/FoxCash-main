package com.foxcash.domain.port.out;

import com.foxcash.domain.model.merchant.MerchantMfaMethod;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saida: repositório de métodos de MFA do lojista no portal (Spec 94).
 * Espelho do {@code MasterMfaMethodRepository} (Spec 44) — sem
 * {@code findByIdAndMasterId} (usado pelo master apenas no step-up, Spec 69,
 * fora de escopo aqui).
 */
public interface MerchantMfaMethodRepository {

    MerchantMfaMethod save(MerchantMfaMethod method);

    Optional<MerchantMfaMethod> findActiveByMerchantId(UUID merchantId);

    Optional<MerchantMfaMethod> findPendingByMerchantId(UUID merchantId);

    /** Remove métodos do lojista (desativação). */
    void deleteByMerchantId(UUID merchantId);
}
