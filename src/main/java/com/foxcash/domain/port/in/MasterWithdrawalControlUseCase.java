package com.foxcash.domain.port.in;

import java.util.UUID;

/**
 * Caso de uso: controle master de saques por lojista (Spec 14).
 *
 * <p>O bloqueio impede novos saques (nao estorna os ja efetivados). Liberar
 * restaura o fluxo automatico. Cada acao gera trilha de auditoria (Spec 37) na
 * mesma transacao — {@code actorId} vem do principal (master.id()) e {@code ip}
 * do request (nunca do body).
 */
public interface MasterWithdrawalControlUseCase {

    void block(UUID merchantId, UUID actorId, String ip);

    void release(UUID merchantId, UUID actorId, String ip);
}
