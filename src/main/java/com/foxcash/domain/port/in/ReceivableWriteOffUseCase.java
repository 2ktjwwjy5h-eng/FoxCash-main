package com.foxcash.domain.port.in;

import com.foxcash.domain.model.Money;

import java.util.UUID;

/**
 * Caso de uso: baixa (write-off) de receivable pelo master (Spec 34) — divida
 * considerada perda pela plataforma, com motivo obrigatorio e auditoria
 * (Spec 37). NUNCA automatico (decisao de negocio).
 */
public interface ReceivableWriteOffUseCase {

    /**
     * Baixa um receivable {@code OPEN}/{@code PARTIAL} como {@code PAID} (por
     * baixa) na mesma transacao: registra {@code CHARGEBACK_LOSS} no ledger do
     * master (Spec 34) e a entrada {@code RECEIVABLE_WRITE_OFF} na trilha de
     * auditoria (Spec 37).
     *
     * @param actorId      master.id() do principal (nunca do body)
     * @param receivableId id do receivable a baixar
     * @param reason       motivo obrigatorio (400 {@code reason_required})
     * @param ip           endereco de origem da requisicao
     * @return valor da perda baixada
     */
    MoneyOutcome writeOff(UUID actorId, UUID receivableId, String reason, String ip);

    /** Valor baixado como perda (response do endpoint). */
    record MoneyOutcome(Money loss) {
    }
}
