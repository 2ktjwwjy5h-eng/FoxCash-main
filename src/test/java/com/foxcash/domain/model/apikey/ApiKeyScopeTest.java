package com.foxcash.domain.model.apikey;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do {@link ApiKeyScope} (Spec 24): escopos textuais das chaves de API,
 * parsing case-insensitive e serialização CSV (ordem estável do enum).
 */
class ApiKeyScopeTest {

    @Test
    void valueReturnsTextualRepresentation() {
        assertThat(ApiKeyScope.CHARGES.value()).isEqualTo("charges");
        assertThat(ApiKeyScope.TRANSACTIONS.value()).isEqualTo("transactions");
        assertThat(ApiKeyScope.WITHDRAWALS.value()).isEqualTo("withdrawals");
    }

    @Test
    void fromIsCaseInsensitive() {
        assertThat(ApiKeyScope.from("charges")).isEqualTo(ApiKeyScope.CHARGES);
        assertThat(ApiKeyScope.from("CHARGES")).isEqualTo(ApiKeyScope.CHARGES);
        assertThat(ApiKeyScope.from("Transactions")).isEqualTo(ApiKeyScope.TRANSACTIONS);
        assertThat(ApiKeyScope.from("withdrawals")).isEqualTo(ApiKeyScope.WITHDRAWALS);
    }

    @Test
    void fromUnknownValueReturnsNull() {
        assertThat(ApiKeyScope.from("refunds")).isNull();
    }

    @Test
    void fromCsvParsesAllScopesAndTrims() {
        Set<ApiKeyScope> scopes = ApiKeyScope.fromCsv("charges, withdrawals");

        assertThat(scopes).containsExactlyInAnyOrder(ApiKeyScope.CHARGES, ApiKeyScope.WITHDRAWALS);
    }

    @Test
    void fromCsvBlankOrNullReturnsEmptySet() {
        assertThat(ApiKeyScope.fromCsv("")).isEmpty();
        assertThat(ApiKeyScope.fromCsv("   ")).isEmpty();
        assertThat(ApiKeyScope.fromCsv(null)).isEmpty();
    }

    @Test
    void toCsvSerializesInEnumOrder() {
        assertThat(ApiKeyScope.toCsv(Set.of(ApiKeyScope.WITHDRAWALS, ApiKeyScope.CHARGES)))
                .isEqualTo("charges,withdrawals");
        assertThat(ApiKeyScope.toCsv(Set.of())).isEqualTo("");
    }
}
