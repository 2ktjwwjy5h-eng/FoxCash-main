package com.foxcash.domain.model.wallet;

/**
 * Tipo de movimentação do ledger do wallet (Spec 27). Cada tipo representa uma
 * origem de crédito/débito do saldo do lojista — imutável e auditável.
 */
public enum WalletLedgerEntryType {

    /** Âncora de reconciliação: saldo existente na implantação do ledger. */
    OPENING_BALANCE,

    /** Venda aprovada (webhook PAID) — crédito no saldo. */
    SALE_CREDIT,

    /** Devolução/refund (webhook REFUNDED) — débito no saldo. */
    REFUND_DEBIT,

    /** Chargeback registrado — débito no saldo. */
    CHARGEBACK_DEBIT,

    /** Saque (lojista ou administrativo) — débito no saldo. */
    WITHDRAWAL_DEBIT,

    /** Cobrança de fatura (Spec 33) — débito no saldo (referência = invoice). */
    INVOICE_FEE_DEBIT,

    /** Recuperação de dívida formal de estorno (Spec 33) — débito no saldo
     *  (referência = receivable; a receita é registrada pelo master, Spec 34). */
    RECEIVABLE_DEBIT,

    /** Estorno de saque FAILED (Spec 36) — crédito no saldo (referência =
     *  withdrawal; o débito havia sido registrado como WITHDRAWAL_DEBIT). */
    WITHDRAWAL_REVERSAL,

    /** Taxa de saque (Spec 88) — débito no saldo (referência = withdrawal; a
     *  receita é registrada pelo master, Spec 34). */
    WITHDRAWAL_FEE_DEBIT,

    /** Devolução da taxa de saque FAILED (Spec 88) — crédito no saldo
     *  (referência = withdrawal; o débito havia sido registrado como
     *  WITHDRAWAL_FEE_DEBIT). */
    WITHDRAWAL_FEE_REVERSAL,

    /** Débito administrativo manual do master (Spec 98) — pode deixar o saldo
     *  negativo (decisão: débito administrativo, padrão estorno Spec 26). */
    ADMIN_DEBIT;
}
