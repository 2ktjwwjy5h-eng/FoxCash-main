package com.foxcash.domain.exception;

/**
 * Falha de resolução do valor do Pagamento via Saldo (Spec 120) — mapeada para
 * HTTP 400 com o código específico: {@code pvs_amount_required} (o EMV não
 * carrega valor e o request não enviou {@code amount}) ou
 * {@code pvs_amount_mismatch} (o EMV carrega valor e o {@code amount} do request
 * diverge — nunca cobrar um valor diferente do que o Pix Copia e Cola embute).
 */
public class PvsAmountException extends RuntimeException {

    private final String code;

    public PvsAmountException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** Código canônico do erro (usado no corpo {@code ErrorResponse}). */
    public String code() {
        return code;
    }
}
