package com.foxcash.domain.model.withdrawal;

/**
 * Tipo de saque (Spec 32).
 *
 * <p>{@code MERCHANT} é o saque do próprio vendedor (exige destino ativo
 * cadastrado na conta dele — Spec 31). {@code ADMINISTRATIVE} é o recolhimento
 * do master em nome do lojista (bloqueio lacrado: passa mesmo com bloqueio
 * ativo / lojista não-ACTIVE; apenas até o saldo — nunca negativo).
 * {@code BILL_PAYMENT} é o Pagamento via Saldo (Spec 120): o lojista paga um
 * Pix Copia e Cola estático com o saldo — destino arbitrário ({@code
 * destination_id} nulo, chave extraída no {@code destination_key_snapshot}),
 * mesmo pipeline de 2 fases/estorno/reconciliação do saque.
 */
public enum WithdrawalType {
    MERCHANT,
    ADMINISTRATIVE,
    BILL_PAYMENT
}
