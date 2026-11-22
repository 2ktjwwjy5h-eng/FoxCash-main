package com.foxcash.domain.port.out;

import com.foxcash.domain.model.withdrawal.WithdrawalDestination;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída: persistência de destinos de saque do lojista (Spec 31).
 * Privada por lojista — buscas sempre escopadas por merchantId.
 */
public interface WithdrawalDestinationRepository {

    WithdrawalDestination save(WithdrawalDestination destination);

    /** Busca pelo id SOMENTE dentro do escopo do lojista (404 para destino de outro lojista). */
    Optional<WithdrawalDestination> findByIdAndMerchantId(UUID id, UUID merchantId);

    List<WithdrawalDestination> findByMerchantId(UUID merchantId);

    /** Quantos destinos ATIVOS o lojista possui (limite da Spec 31). */
    long countActiveByMerchantId(UUID merchantId);
}
