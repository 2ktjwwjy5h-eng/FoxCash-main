package com.foxcash.domain.exception;

import java.util.UUID;

/**
 * Saques bloqueados para lojista honeypot (Spec 98 — D4.5): a conta em ambiente
 * controlado de monitoramento antifraude NUNCA saca (nem o vendedor nem o saque
 * administrativo do master — ninguém movimenta dinheiro de honeypot). Mapeado
 * para 409 {@code honeypot_blocked}.
 */
public class HoneypotBlockedException extends RuntimeException {

    public HoneypotBlockedException(UUID merchantId) {
        super("Withdrawals are blocked for honeypot merchant " + merchantId);
    }
}
