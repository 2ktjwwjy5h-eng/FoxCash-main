package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.model.withdrawal.WithdrawalDestination;
import com.foxcash.domain.model.withdrawal.WithdrawalDestinationType;

import java.util.regex.Pattern;

/**
 * Aplica mascara ao destino de saque (Spec 31) — a resposta NUNCA expoe o
 * valor em claro. Formato por tipo:
 * <ul>
 *   <li>PIX_CPF: 529.***.***-25</li>
 *   <li>PIX_CNPJ: 12.***.*** /0001-00 (mascara sem fechar o comentario)</li>
 *   <li>PIX_PHONE: +55 11 *****-9999</li>
 *   <li>PIX_EVP: 4 primeiros + ... + 4 ultimos</li>
 *   <li>BANK_ACCOUNT: Banco 341 - ag 0001 - conta ***-9</li>
 * </ul>
 *
 * <p>Spec 32: {@link #maskSnapshot(String)} mascara o snapshot persistido do
 * saque (formato {@code "<TIPO>:<chave bruta>"}) — o response do saque nunca
 * devolve a chave em claro.
 */
public final class WithdrawalDestinationMasker {

    private static final Pattern NON_DIGITS = Pattern.compile("\\D");

    private WithdrawalDestinationMasker() {
    }

    public static String mask(WithdrawalDestination destination) {
        return maskKey(destination.type(), destination.snapshotRawKey());
    }

    /** Mascara do snapshot persistido (Spec 32) — {@code "<TIPO>:<chave bruta>"}. */
    public static String maskSnapshot(String snapshot) {
        if (snapshot == null) {
            return null;
        }
        int colon = snapshot.indexOf(':');
        if (colon <= 0) {
            return "***";
        }
        WithdrawalDestinationType type = parseType(snapshot.substring(0, colon));
        if (type == null) {
            return "***";
        }
        return maskKey(type, snapshot.substring(colon + 1));
    }

    private static WithdrawalDestinationType parseType(String raw) {
        try {
            return WithdrawalDestinationType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String maskKey(WithdrawalDestinationType type, String rawKey) {
        return switch (type) {
            // CPF/CNPJ: delegado ao DocumentMasker (Spec 97, T1.3 — DRY; mesmo
            // formato da Spec 31: 529.***.***-25 / 12.***.***/0001-00).
            case PIX_CPF, PIX_CNPJ -> DocumentMasker.mask(rawKey);
            case PIX_PHONE -> {
                String digits = onlyDigits(rawKey);
                if (digits.length() < 12) {
                    yield "***";
                }
                yield "+55 " + digits.substring(2, 4) + " *****-" + digits.substring(digits.length() - 4);
            }
            case PIX_EVP -> {
                if (rawKey == null || rawKey.length() < 8) {
                    yield "***";
                }
                yield rawKey.substring(0, 4) + "..." + rawKey.substring(rawKey.length() - 4);
            }
            case BANK_ACCOUNT -> maskBank(rawKey);
            default -> "***";
        };
    }

    /** {@code rawKey} do banco: {@code "banco|agencia|conta|tipo"}. */
    private static String maskBank(String rawKey) {
        String[] parts = rawKey == null ? new String[0] : rawKey.split("\\|");
        String bankCode = parts.length > 0 ? parts[0] : null;
        String agency = parts.length > 1 ? parts[1] : null;
        String account = parts.length > 2 ? parts[2] : null;
        String digits = onlyDigits(account);
        String accountTail = digits.length() >= 2 ? digits.substring(digits.length() - 2) : (digits + "*");
        return "Banco " + safe(bankCode) + " - ag " + safe(agency)
                + " - conta ***-" + accountTail;
    }

    private static String onlyDigits(String value) {
        return value == null ? "" : NON_DIGITS.matcher(value).replaceAll("");
    }

    private static String safe(String value) {
        return value == null ? "?" : value;
    }
}
