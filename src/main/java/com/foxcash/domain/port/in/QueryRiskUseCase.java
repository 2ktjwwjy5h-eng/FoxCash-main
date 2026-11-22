package com.foxcash.domain.port.in;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.risk.MerchantRiskSnapshot;
import com.foxcash.domain.model.risk.RiskSearchQuery;

import java.util.Optional;
import java.util.UUID;

/**
 * Caso de uso: consulta de snapshots de risco (Spec 16) — somente-leitura.
 */
public interface QueryRiskUseCase {

    PageResult<MerchantRiskSnapshot> search(RiskSearchQuery query);

    Optional<MerchantRiskSnapshot> latestByMerchant(UUID merchantId);
}
