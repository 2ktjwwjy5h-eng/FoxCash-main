package com.foxcash.domain.pvs;

import java.math.BigDecimal;

/**
 * Resultado do parse de um Pix Copia e Cola ESTÁTICO (Spec 120) — a chave Pix
 * extraída do EMV BR Code (TLV 26.01), o tipo canônico (mesmas regras da Spec
 * 31/47: {@code cpf}/{@code cnpj}/{@code phone}/{@code evp}) e o valor do campo
 * 54 (opcional — {@code null} quando o brcode não carrega valor).
 */
public record ParsedPix(String key, String keyType, BigDecimal amount) {
}
