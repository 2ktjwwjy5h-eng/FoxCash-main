package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.model.withdrawal.WithdrawalDestination;
import com.foxcash.domain.model.withdrawal.WithdrawalDestinationType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do {@link WithdrawalDestinationMasker} (Specs 31/32): a resposta REST
 * NUNCA expõe o valor em claro — máscara por tipo e do snapshot persistido.
 */
class WithdrawalDestinationMaskerTest {

    private static final UUID MERCHANT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-05T10:00:00Z");

    private WithdrawalDestination destination(WithdrawalDestinationType type, String value) {
        return WithdrawalDestination.create(MERCHANT, type, value, null, null, null, null, "label", NOW);
    }

    private WithdrawalDestination bank(String bankCode, String agency, String account) {
        return WithdrawalDestination.create(MERCHANT, WithdrawalDestinationType.BANK_ACCOUNT,
                null, bankCode, agency, account, "cc", "conta", NOW);
    }

    @Test
    void masksCpfKey() {
        assertThat(WithdrawalDestinationMasker.mask(destination(WithdrawalDestinationType.PIX_CPF, "52998224725")))
                .isEqualTo("529.***.***-25");
    }

    @Test
    void masksCnpjKey() {
        assertThat(WithdrawalDestinationMasker.mask(destination(WithdrawalDestinationType.PIX_CNPJ, "11222333000181")))
                .isEqualTo("11.***.***/0001-81");
    }

    @Test
    void masksPhoneKey() {
        assertThat(WithdrawalDestinationMasker.mask(destination(WithdrawalDestinationType.PIX_PHONE, "+55 11 98765-4321")))
                .isEqualTo("+55 11 *****-4321");
        // Borda: exatamente 12 dígitos também é mascarado (limite é < 12).
        assertThat(WithdrawalDestinationMasker.mask(destination(WithdrawalDestinationType.PIX_PHONE, "+55 11 9876-5432")))
                .isEqualTo("+55 11 *****-5432");
    }

    @Test
    void masksEvpKey() {
        String evp = "12345678-1234-1234-1234-123456789012";

        assertThat(WithdrawalDestinationMasker.mask(destination(WithdrawalDestinationType.PIX_EVP, evp)))
                .isEqualTo("1234...9012");
        // Borda: exatamente 8 caracteres é mascarado (limite é < 8).
        assertThat(WithdrawalDestinationMasker.mask(destination(WithdrawalDestinationType.PIX_EVP, "12345678")))
                .isEqualTo("1234...5678");
    }

    @Test
    void masksBankAccount() {
        assertThat(WithdrawalDestinationMasker.mask(bank("341", "0001", "12345-9")))
                .isEqualTo("Banco 341 - ag 0001 - conta ***-59");
        // Borda: conta com exatamente 2 dígitos usa os 2 últimos.
        assertThat(WithdrawalDestinationMasker.mask(bank("001", "1234", "12")))
                .isEqualTo("Banco 001 - ag 1234 - conta ***-12");
    }

    @Test
    void masksBankAccountSnapshotWithMissingParts() {
        // Snapshot persistido com partes ausentes (formato "<TIPO>:<chave bruta>"):
        // só banco, e banco+agência (sem conta).
        assertThat(WithdrawalDestinationMasker.maskSnapshot("BANK_ACCOUNT:341"))
                .isEqualTo("Banco 341 - ag ? - conta ***-*");
        assertThat(WithdrawalDestinationMasker.maskSnapshot("BANK_ACCOUNT:341|0001"))
                .isEqualTo("Banco 341 - ag 0001 - conta ***-*");
    }

    @Test
    void shortBankAccountTailAppendsStar() {
        assertThat(WithdrawalDestinationMasker.mask(bank("001", "1234", "9")))
                .isEqualTo("Banco 001 - ag 1234 - conta ***-9*");
    }

    @Test
    void invalidCpfReturnsStars() {
        assertThat(WithdrawalDestinationMasker.mask(destination(WithdrawalDestinationType.PIX_CPF, "123")))
                .isEqualTo("***");
    }

    @Test
    void maskSnapshotMasksStoredSnapshot() {
        assertThat(WithdrawalDestinationMasker.maskSnapshot("PIX_CPF:52998224725"))
                .isEqualTo("529.***.***-25");
        // PIX_EVP não passa por remoção de não-dígitos — o slice após o ':' é exato.
        assertThat(WithdrawalDestinationMasker.maskSnapshot("PIX_EVP:abcdefgh"))
                .isEqualTo("abcd...efgh");
    }

    @Test
    void maskSnapshotUnknownTypeReturnsStars() {
        assertThat(WithdrawalDestinationMasker.maskSnapshot("TED:12345")).isEqualTo("***");
        assertThat(WithdrawalDestinationMasker.maskSnapshot("no-colon")).isEqualTo("***");
        assertThat(WithdrawalDestinationMasker.maskSnapshot(":value")).isEqualTo("***");
    }

    @Test
    void maskSnapshotNullReturnsNull() {
        assertThat(WithdrawalDestinationMasker.maskSnapshot(null)).isNull();
    }
}
