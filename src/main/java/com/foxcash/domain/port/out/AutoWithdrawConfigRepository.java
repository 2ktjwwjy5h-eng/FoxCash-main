package com.foxcash.domain.port.out;

import com.foxcash.domain.model.withdrawal.AutoWithdrawConfig;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída: persistência da config de saque automático por limiar de
 * saldo (Spec 70) — 1:1 por lojista. Buscas escopadas por merchantId (o portal
 * sempre usa o principal; sem IDOR).
 */
public interface AutoWithdrawConfigRepository {

    Optional<AutoWithdrawConfig> findByMerchantId(UUID merchantId);

    /** Configs com {@code enabled=true} — o job itera sobre elas a cada execução. */
    List<AutoWithdrawConfig> findEnabled();

    AutoWithdrawConfig save(AutoWithdrawConfig config);
}
