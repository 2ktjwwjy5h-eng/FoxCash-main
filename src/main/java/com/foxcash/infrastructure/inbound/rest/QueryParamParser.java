package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.model.billing.BillingPeriod;

import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Parsing de parametros de consulta REST — compartilhado pelos controllers do
 * master (Specs 14/15). Parâmetros inválidos viram {@link IllegalArgumentException}
 * (→ 400 {@code invalid_argument}, mesmo contrato do AdminController).
 */
final class QueryParamParser {

    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    private QueryParamParser() {
    }

    static int clampPage(int page) {
        return Math.max(page, DEFAULT_PAGE);
    }

    static int clampSize(int size) {
        return Math.min(Math.max(size, 1), MAX_SIZE);
    }

    static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String paramName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Parâmetro inválido '" + paramName + "': '" + value + "'");
        }
    }

    static Instant parseInstant(String value, String paramName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(
                    "Parâmetro inválido '" + paramName + "': '" + value + "' (esperado ISO-8601)");
        }
    }

    static BillingPeriod parsePeriod(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return BillingPeriod.of(YearMonth.parse(value.trim()));
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(
                    "Parâmetro inválido 'month': '" + value + "' (esperado yyyy-MM)");
        }
    }

    static UUID parseUuid(String value, String paramName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Parâmetro inválido '" + paramName + "': '" + value + "'");
        }
    }

    /**
     * Valida IPv4 (4 octetos 0-255) ou IPv6 (hex + ':') — mesmo critério do
     * Operator Bot (Spec 67, {@code !ip}; D2.3 da Spec 71). Extraído do
     * {@code AdminController} (DRY — comportamento idêntico).
     */
    static boolean isValidIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        if (ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            for (String octet : ip.split("\\.")) {
                if (Integer.parseInt(octet) > 255) {
                    return false;
                }
            }
            return true;
        }
        return ip.matches("[0-9a-fA-F:]+") && ip.contains(":");
    }
}
