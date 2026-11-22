package com.foxcash.domain.port.out;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.risk.MerchantRiskSnapshot;
import com.foxcash.domain.model.risk.RiskSearchQuery;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saida: persistencia de snapshots de risco (Spec 16).
 */
public interface RiskRepository {

    MerchantRiskSnapshot save(MerchantRiskSnapshot snapshot);

    PageResult<MerchantRiskSnapshot> search(RiskSearchQuery query);

    Optional<MerchantRiskSnapshot> latestByMerchant(UUID merchantId);
}
