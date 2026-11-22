package com.foxcash.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracao de cobranca/faturamento (Spec 33) — {@code foxcash.billing.*}.
 *
 * <p>{@code block-withdrawals-on-debt}: lojista com receivable
 * {@code OPEN}/{@code PARTIAL} (divida formal com a plataforma) nao saca —
 * 409 {@code outstanding_debt}. Default {@code true}; desligavel para operacao
 * apenas-consulta (o master continua recolhendo via saque administrativo).
 */
@ConfigurationProperties("foxcash.billing")
public class BillingProperties {

    private boolean blockWithdrawalsOnDebt = true;

    public boolean isBlockWithdrawalsOnDebt() {
        return blockWithdrawalsOnDebt;
    }

    public void setBlockWithdrawalsOnDebt(boolean blockWithdrawalsOnDebt) {
        this.blockWithdrawalsOnDebt = blockWithdrawalsOnDebt;
    }
}
