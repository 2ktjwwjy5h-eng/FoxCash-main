package com.foxcash.domain.exception;

/**
 * Lead bloqueou o bot (Spec 83 D6) — Telegram 403 "bot was blocked by the user"
 * no envio (ou falha de DM por bloqueio no Discord). O envio do painel é
 * recusado com 409 {@code lead_blocked} e o lead é marcado {@code BLOQUEADO}
 * com auditoria {@code LEAD_BLOCKED}.
 */
public class LeadBlockedException extends RuntimeException {

    public LeadBlockedException(String message) {
        super(message);
    }
}
