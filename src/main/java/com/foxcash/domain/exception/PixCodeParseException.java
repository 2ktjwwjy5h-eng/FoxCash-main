package com.foxcash.domain.exception;

/**
 * Falha no parse de um Pix Copia e Cola (Spec 120) — o código é o erro canônico
 * da resposta HTTP (400): {@code pvs_invalid_code} (não é um EMV BR Code
 * estático válido — prefixo/TLV/CRC/chave ausente), {@code pvs_dynamic_pix_not_supported}
 * (payload dinâmico com URL {@code br.gov.bcb.pix} — fora do escopo do
 * Pagamento via Saldo) ou {@code pvs_unsupported_key} (chave em formato fora de
 * CPF/CNPJ/phone/EVP).
 */
public class PixCodeParseException extends RuntimeException {

    private final String code;

    public PixCodeParseException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** Código canônico do erro (usado no corpo {@code ErrorResponse}). */
    public String code() {
        return code;
    }
}
