package com.foxcash.domain.exception;

import java.util.UUID;

/**
 * Nenhum snapshot de risco para o lojista (Spec 16) — mapeado para 404
 * {@code risk_snapshot_not_found}.
 */
public class RiskSnapshotNotFoundException extends RuntimeException {

    public RiskSnapshotNotFoundException(UUID merchantId) {
        super("No risk snapshot found for merchant " + merchantId);
    }
}
