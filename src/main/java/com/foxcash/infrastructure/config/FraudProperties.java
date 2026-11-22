package com.foxcash.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Regras do antifraude em tempo real (Spec 19) — {@code foxcash.fraud.*}.
 *
 * <p>Filosofia (ajuste 2026-08-04): a <b>unica</b> regra de bloqueio e o
 * historico de chargeback do comprador (quem ja deu chargeback nao volta pela
 * mesma porta). <b>Velocidade foi removida</b> — compra rapida em sequencia e
 * comportamento normal dos clientes, e bloquear venda legitima e inaceitavel.
 * A auto-suspensao do lojista por chargeback alto vive em
 * {@code foxcash.risk.auto-suspend-enabled} (RiskProperties).
 */
@ConfigurationProperties("foxcash.fraud")
public class FraudProperties {

    /** Janela (dias) para contar chargebacks de um comprador (bloqueio). */
    private int buyerChargebackWindowDays = 90;

    /** Chargebacks de um comprador na janela >= este valor bloqueiam novas cobrancas. */
    private int buyerChargebackBlockCount = 1;

    public int getBuyerChargebackWindowDays() {
        return buyerChargebackWindowDays;
    }

    public void setBuyerChargebackWindowDays(int buyerChargebackWindowDays) {
        this.buyerChargebackWindowDays = buyerChargebackWindowDays;
    }

    public int getBuyerChargebackBlockCount() {
        return buyerChargebackBlockCount;
    }

    public void setBuyerChargebackBlockCount(int buyerChargebackBlockCount) {
        this.buyerChargebackBlockCount = buyerChargebackBlockCount;
    }
}
