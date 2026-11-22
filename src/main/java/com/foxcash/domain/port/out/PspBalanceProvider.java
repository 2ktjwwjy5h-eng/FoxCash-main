package com.foxcash.domain.port.out;

import java.math.BigDecimal;

/**
 * Saldo da conta master no PSP (Spec 92 — sweep zero-balance). A conta master
 * é onde caem as cobranças Pix dos lojistas; o saldo "Disponível" é o que o
 * job de sweep transfere para a conta blindada corporativa (retendo o buffer).
 *
 * <p>Selecionado pelo MESMO provedor de cobrança ({@code foxcash.psp.provider}):
 * o saldo pertence à conta do adquirente que recebe os pagamentos.
 */
public interface PspBalanceProvider {

    /** Saldo atual da conta master no PSP. */
    PspBalance getBalance();

    /** {@code available} = disponível p/ transferência; {@code blocked} = retido
     * (opcional, ex.: saldo em disputa); {@code currency} = ISO 4217 (BRL). */
    record PspBalance(BigDecimal available, BigDecimal blocked, String currency) {
    }

    /** Erro real/timeout ao consultar o saldo (Spec 92) — o job de sweep trata
     * como falha (job FAILED + alerta), nunca derruba o scheduler. */
    class PspBalanceException extends RuntimeException {
        public PspBalanceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
