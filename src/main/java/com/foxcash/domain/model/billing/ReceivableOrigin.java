package com.foxcash.domain.model.billing;

/**
 * Origem da divida formal do lojista com a plataforma (Spec 33).
 *
 * <p>Cada receivable tem UMA origem explicita e uma referencia ({@code
 * reference_id}): {@code INVOICE} aponta para a fatura nao coberta no
 * vencimento; {@code CHARGEBACK}/{@code REFUND} apontam para o estorno que
 * deixou o saldo do lojista negativo (decisao Spec 26/29); {@code ADJUSTMENT}
 * fica reservado para acoes administrativas futuras.
 */
public enum ReceivableOrigin {
    INVOICE,
    CHARGEBACK,
    REFUND,
    ADJUSTMENT
}
