package com.foxcash.domain.port.in;

import com.foxcash.domain.model.Money;

import java.util.UUID;

/**
 * Caso de uso: Pagamento via Saldo (PVS, Spec 120) — o lojista paga um Pix
 * Copia e Cola ESTÁTICO com o saldo do wallet (destino arbitrário, sem
 * cadastrar destino). É uma variação do saque: nasce como {@code withdrawal}
 * de tipo {@code BILL_PAYMENT}, passa pelo mesmo pipeline de 2 fases, estorno
 * automático (valor + taxa, Spec 88) e reconciliação.
 *
 * <p>Anti-abuso: teto diário ({@code foxcash.pvs.daily-cap}) e teto por
 * operação ({@code foxcash.pvs.max-per-payment}) avaliados dentro da Fase A
 * sob o lock do lojista (SEC-19). {@code pixCode} nunca é logado.
 */
public interface PvsPaymentUseCase {

    /** Paga um Pix Copia e Cola estático com o saldo do wallet. */
    Result pay(PayCommand command);

    /** Limites do dia (teto diário, usado, restante e teto por operação). */
    Limits limits(UUID merchantId);

    record PayCommand(UUID merchantId, String pixCode, Money amount) {
    }

    record Result(UUID withdrawalId, Money amount, Money fee, Money walletBalance, String status) {
    }

    record Limits(Money dailyCap, Money usedToday, Money remaining, Money maxPerPayment) {
    }
}
