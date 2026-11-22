package com.foxcash.domain.model.apikey;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Escopos de uma chave de API de lojista (Spec 24). A chave do cadastro tem
 * escopo total (os três). Escopos novos restringem as rotas que a chave pode
 * acessar — validação no {@code ApiKeyAuthFilter}.
 */
public enum ApiKeyScope {

    /** Criação de cobrança: {@code POST /api/v1/foxcash/pix/**}. */
    CHARGES("charges"),

    /** Leitura de transações: {@code /api/v1/transactions/**} e
     *  {@code /api/v1/merchants/{id}/transactions}. */
    TRANSACTIONS("transactions"),

    /** Saques: {@code /api/v1/merchants/{id}/withdrawals/**}. */
    WITHDRAWALS("withdrawals");

    private final String value;

    ApiKeyScope(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** Converte um escopo textual; {@code null} se desconhecido. */
    public static ApiKeyScope from(String value) {
        for (ApiKeyScope scope : values()) {
            if (scope.value.equalsIgnoreCase(value)) {
                return scope;
            }
        }
        return null;
    }

    /** Converte uma lista CSV (ex.: "charges,withdrawals") em um conjunto. */
    public static Set<ApiKeyScope> fromCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .map(ApiKeyScope::from)
                .collect(Collectors.toSet());
    }

    /** Serializa um conjunto em CSV (ordem estável do enum). */
    public static String toCsv(Set<ApiKeyScope> scopes) {
        return Arrays.stream(values())
                .filter(scopes::contains)
                .map(ApiKeyScope::value)
                .collect(Collectors.joining(","));
    }
}
