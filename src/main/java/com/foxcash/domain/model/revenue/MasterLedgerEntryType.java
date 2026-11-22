package com.foxcash.domain.model.revenue;

/**
 * Tipo de movimentacao do ledger do master (Spec 34) — receita/perda da
 * plataforma por lojista. Cada tipo representa a origem da receita/perda,
 * registrada na mesma transacao do evento financeiro.
 */
public enum MasterLedgerEntryType {

    /** Taxa de transacao (R$ 0,50 x vendas) — fatura paga (Spec 33). */
    TRANSACTION_FEE,

    /** Mensalidade (R$ 69,95) — fatura paga. */
    MONTHLY_FEE,

    /** Receivable de estorno pago (valor efetivamente recebido). */
    RECEIVABLE_RECOVERY,

    /** Saque administrativo do master (Spec 32) — valor recolhido. */
    ADMIN_RECOVERY,

    /**
     * Reversão do saque administrativo que falhou (SEC-29, revisão de segurança
     * 2026-08-11): o estorno devolve o valor ao lojista — a receita registrada
     * em {@link #ADMIN_RECOVERY} na Fase A precisa ser revertida, senão o
     * relatório de receita (Spec 34) mostra valor recolhido que não ocorreu.
     */
    ADMIN_RECOVERY_REVERSAL,

    /** Taxa de saque (Spec 88) — R$ 2,50 por saque do lojista (manual ou
     *  automático; administrativo é isento), registrada na Fase A do saque. */
    WITHDRAWAL_FEE,

    /**
     * Reversão da taxa de saque que falhou (Spec 88 — mesmo padrão do
     * {@link #ADMIN_RECOVERY_REVERSAL}, SEC-29): o estorno devolve valor + taxa
     * ao lojista — a receita registrada em {@link #WITHDRAWAL_FEE} na Fase A
     * precisa ser revertida, senão o relatório de receita mostra taxa cobrada
     * que não ocorreu.
     */
    WITHDRAWAL_FEE_REVERSAL,

    /** Baixa (write-off) de receivable — divida considerada perda pelo master. */
    CHARGEBACK_LOSS,

    /** Débito administrativo manual do master (Spec 98) — receita da plataforma
     *  (valor debitado do wallet do lojista; balance_after acompanha). */
    ADMIN_DEBIT;
}
