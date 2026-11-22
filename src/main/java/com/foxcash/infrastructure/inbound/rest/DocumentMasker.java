package com.foxcash.infrastructure.inbound.rest;

import java.util.regex.Pattern;

/**
 * Mascara de documento (CPF/CNPJ) para respostas do master (Spec 97, T1.3) —
 * extraida do {@link WithdrawalDestinationMasker} (Spec 31) para uso generico
 * no painel de usuarios: o {@code merchants.document} (criptografado em
 * repouso) nunca sai em claro. Formato por tamanho:
 * <ul>
 *   <li>11 digitos (CPF): 529.***.***-25</li>
 *   <li>14 digitos (CNPJ): 12.***.***-0001-00 (mascara sem fechar o comentario)</li>
 *   <li>outro: ***</li>
 * </ul>
 */
public final class DocumentMasker {

    private static final Pattern NON_DIGITS = Pattern.compile("\\D");

    private DocumentMasker() {
    }

    /** Mascara um documento (CPF/CNPJ); {@code null} → {@code null}. */
    public static String mask(String document) {
        if (document == null) {
            return null;
        }
        String digits = NON_DIGITS.matcher(document).replaceAll("");
        return switch (digits.length()) {
            case 11 -> digits.substring(0, 3) + ".***.***-" + digits.substring(9);
            case 14 -> digits.substring(0, 2) + ".***.***/" + digits.substring(8, 12) + "-" + digits.substring(12);
            default -> "***";
        };
    }
}
