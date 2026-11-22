package com.foxcash.domain.exception;

import com.foxcash.domain.model.Money;

import java.util.UUID;

/**
 * Auto-saque (Spec 70): o saldo disponível do lojista está ABAIXO do limiar da
 * config — avaliado DENTRO do lock pessimista (corrida com refund/estorno).
 *
 * <p><b>Não mapeada no {@code GlobalExceptionHandler}:</b> é uma condição de
 * JOB (skip silencioso), nunca uma resposta HTTP — o {@code
 * AutoWithdrawRunnerService} a trata como skip e segue.
 */
public class AutoWithdrawBelowThresholdException extends RuntimeException {

    public AutoWithdrawBelowThresholdException(UUID merchantId, Money balance, Money threshold) {
        super("Merchant " + merchantId + " wallet balance " + balance.amount()
                + " below auto-withdraw threshold " + threshold.amount());
    }
}
