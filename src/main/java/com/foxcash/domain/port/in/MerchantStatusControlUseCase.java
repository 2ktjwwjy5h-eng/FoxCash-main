package com.foxcash.domain.port.in;

import java.util.UUID;

/**
 * Caso de uso: controle master do status do lojista (Spec 16, ADR-008).
 *
 * <p>Suspender coloca o lojista em {@code SUSPENDED} (nao processa pagamentos
 * nem saca); liberar restaura {@code ACTIVE}. Decisao do operador — o
 * monitoramento de risco apenas sinaliza os indicadores. Toda acao gera trilha
 * de auditoria {@code RISK_ADJUSTMENT} (Spec 37).
 */
public interface MerchantStatusControlUseCase {

    void suspend(UUID merchantId, UUID actorId, String ip);

    void release(UUID merchantId, UUID actorId, String ip);
}
