package com.foxcash.domain.port.in;

/**
 * Caso de uso: monitoramento de risco (Spec 16, ADR-008). Computa os
 * indicadores de risco de TODOS os lojistas (a partir dos dados existentes)
 * e grava um snapshot por lojista — o operador (master) decide a acao.
 */
public interface RiskMonitoringUseCase {

    /**
     * Calcula e persiste um snapshot de risco para cada lojista.
     *
     * @return quantidade de snapshots gravados
     */
    int computeForAllMerchants();
}
