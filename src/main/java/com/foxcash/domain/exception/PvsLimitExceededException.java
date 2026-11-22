package com.foxcash.domain.exception;

/**
 * Limite de anti-abuso do Pagamento via Saldo excedido (Spec 120) — mapeado
 * para HTTP 409 com o código específico: {@code pvs_max_per_payment_exceeded}
 * (valor + taxa acima do teto por operação) ou {@code pvs_daily_cap_exceeded}
 * (soma dos PVS do dia acima do teto diário). Avaliado DENTRO da Fase A sob o
 * lock pessimista do lojista (SEC-19) — sem corrida entre PVS concorrentes.
 */
public class PvsLimitExceededException extends RuntimeException {

    private final String code;

    public PvsLimitExceededException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** Código canônico do limite violado (usado no corpo {@code ErrorResponse}). */
    public String code() {
        return code;
    }
}
