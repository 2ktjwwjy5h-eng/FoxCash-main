package com.foxcash.domain.model.withdrawal;

/**
 * Tipo de destino de saque do lojista (Spec 31).
 *
 * <p>Os 4 primeiros são os tipos de chave Pix do Bacen; {@code BANK_ACCOUNT} é
 * um endereço de transferência bancária (TED futura) com chave Pix associada
 * opcional. O valor em claro nunca é exposto — a listagem devolve máscara.
 */
public enum WithdrawalDestinationType {
    /** Chave Pix = CPF (11 dígitos, dígitos verificadores). */
    PIX_CPF,

    /** Chave Pix = CNPJ (14 dígitos, dígitos verificadores). */
    PIX_CNPJ,

    /** Chave Pix = celular BR (formato +55 DDD número). */
    PIX_PHONE,

    /** Chave Pix = aleatória (UUID). */
    PIX_EVP,

    /** Dados bancários: banco + agência + conta (chave Pix associada opcional). */
    BANK_ACCOUNT;
}
