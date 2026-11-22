package com.foxcash.domain.port.out;

import com.foxcash.domain.model.fraud.Chargeback;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saida: persistencia de chargebacks (Spec 19).
 */
public interface ChargebackRepository {

    Chargeback save(Chargeback chargeback);

    Optional<Chargeback> findById(UUID id);

    Optional<Chargeback> findByTransactionId(UUID transactionId);

    /** Total de chargebacks de um lojista (indicador de risco, Spec 16/19). */
    long countByMerchantId(UUID merchantId);

    /** Chargebacks de um lojista desde uma data — janela do MED (Spec 128). */
    long countByMerchantIdAndCreatedAtAfter(UUID merchantId, Instant since);

    /** Chargebacks de um comprador (pseudonimo) desde uma data — triagem (Spec 19). */
    long countByBuyerIdHashSince(String buyerIdHash, Instant since);

    /** Chargebacks mais recentes (revisao do master), limitado pelo chamador. */
    List<Chargeback> findRecent(int limit);
}
