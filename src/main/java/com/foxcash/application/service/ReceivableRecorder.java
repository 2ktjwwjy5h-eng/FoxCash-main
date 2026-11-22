package com.foxcash.application.service;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.billing.Receivable;
import com.foxcash.domain.model.billing.ReceivableOrigin;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.port.out.ReceivableRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Formaliza a divida do lojista com a plataforma quando o estorno (Spec 29)
 * deixa o saldo negativo (Spec 33).
 *
 * <p>Chamado na MESMA transacao do debito (chargeback/refund): se o saldo
 * resultante for negativo, cria um receivable {@code CHARGEBACK}/{@code REFUND}
 * no valor do saldo devedor (a divida e o modulo do negativo). Idempotente —
 * um receivable ja aberto para a origem nao e duplicado (a unique
 * {@code uq_receivables_origin} e o backstop).
 */
@Service
public class ReceivableRecorder {

    private final ReceivableRepository receivableRepository;

    public ReceivableRecorder(ReceivableRepository receivableRepository) {
        this.receivableRepository = receivableRepository;
    }

    /**
     * Cria receivable de estorno se {@code merchant.walletBalance()} for
     * negativo (o lojista deve a plataforma o valor ja sacado e devolvido).
     */
    public void recordFromNegativeBalance(Merchant merchant, ReceivableOrigin origin,
                                          UUID referenceId, Instant now) {
        if (merchant.walletBalance().amount().signum() >= 0) {
            return;
        }
        if (receivableRepository.findOpenByOrigin(merchant.id(), origin, referenceId).isPresent()) {
            return;
        }
        Money debt = Money.of(merchant.walletBalance().amount().negate());
        receivableRepository.save(Receivable.create(merchant.id(), origin, referenceId, debt, now));
    }
}
