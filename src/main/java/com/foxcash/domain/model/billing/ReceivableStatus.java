package com.foxcash.domain.model.billing;

/**
 * Status da divida formal (Spec 33).
 *
 * <p>{@code OPEN} = nada pago; {@code PARTIAL} = pagamento parcial recebido
 * (0 &lt; paid_amount &lt; amount); {@code PAID} = quitada. Uma divida
 * {@code OPEN}/{@code PARTIAL} bloqueia os saques do vendedor (configuravel).
 */
public enum ReceivableStatus {
    OPEN,
    PARTIAL,
    PAID
}
